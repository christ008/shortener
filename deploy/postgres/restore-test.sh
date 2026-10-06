#!/bin/sh
# Proves that the latest backup restores: restores it into an empty directory, lets the server recover, counts the links and
# throws the copy away. The data directory carries the include of diagnostics.conf, so the file is mounted as it is on the server. Run it with `deploy/postgres/backup restore-test`, which starts it in a throwaway container of this image
# with the repository mounted read only. A backup nobody has restored is a hope.
#
# It replays the WAL of the repository, so the links it counts are those archived, which trail the primary by at most
# archive_timeout (5 minutes).
set -eu
data=/tmp/restored
mkdir -p "$data"
chmod 0700 "$data"
chown postgres:postgres "$data"

echo "restore-test: restoring the latest backup" >&2
gosu postgres pgbackrest --stanza=shortener --pg1-path="$data" --log-level-console=warn --log-level-file=off restore

echo "restore-test: starting the copy and replaying the archived WAL" >&2
gosu postgres pg_ctl -D "$data" -w -t 300 -l /tmp/restored.log \
  -o "-c archive_mode=off -c listen_addresses='' -c unix_socket_directories=/tmp -c port=5499" start >/dev/null

# A recovering server accepts connections only when it is done, and then promotes itself.
count=$(gosu postgres psql -h /tmp -p 5499 -U postgres -d shortener -tAc 'SELECT count(*) FROM short_link')
newest=$(gosu postgres psql -h /tmp -p 5499 -U postgres -d shortener -tAc "SELECT coalesce(max(created_at)::text, 'no links') FROM short_link")
gosu postgres pg_ctl -D "$data" -m fast -w stop >/dev/null
echo "restore-test: ok, the copy has $count links, the newest created at $newest"
