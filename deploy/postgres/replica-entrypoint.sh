#!/bin/sh
# Starts a streaming replica of the primary. The first time, with an empty data directory, it takes a copy of the primary with
# pg_basebackup and creates a replication slot there, so that the primary keeps the WAL the replica has not read; after that it
# only starts, and follows. It authenticates as shortener_replicator, whose password is the file REPLICATOR_PASSWORD_FILE
# (a Docker secret), through a password file made in memory. The primary is `postgres` unless PRIMARY_HOST says otherwise.
#
# A replica is read only. To promote it when the primary is lost, see docs/DEPLOY.md.
set -eu
primary=${PRIMARY_HOST:-postgres}
password_file=${REPLICATOR_PASSWORD_FILE:?REPLICATOR_PASSWORD_FILE must name the file with the replication password}
pgpass=/tmp/.pgpass

printf '%s:5432:*:shortener_replicator:%s\n' "$primary" "$(cat "$password_file")" >"$pgpass"
chmod 0600 "$pgpass"
chown postgres:postgres "$pgpass"

if [ ! -s "$PGDATA/PG_VERSION" ]; then
  until gosu postgres env PGPASSFILE="$pgpass" pg_isready -q -h "$primary" -U shortener_replicator; do
    echo "replica: waiting for $primary" >&2
    sleep 2
  done
  mkdir -p "$PGDATA"
  chown postgres:postgres "$PGDATA"
  chmod 0700 "$PGDATA"
  echo "replica: copying $primary" >&2
  gosu postgres env PGPASSFILE="$pgpass" pg_basebackup -h "$primary" -U shortener_replicator -D "$PGDATA" \
    -X stream -R -C -S "${REPLICATION_SLOT:-replica1}" --checkpoint=fast
fi

exec docker-entrypoint.sh postgres \
  -c hot_standby=on \
  -c "primary_conninfo=host=$primary port=5432 user=shortener_replicator passfile=$pgpass application_name=${REPLICATION_SLOT:-replica1}" \
  -c "primary_slot_name=${REPLICATION_SLOT:-replica1}"
