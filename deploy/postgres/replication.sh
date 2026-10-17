#!/bin/sh
# Creates the replication-only role shortener_replicator and admits it in pg_hba.conf. Runs when the data directory is first
# initialised; on an existing database, see docs/DEPLOY.md.
# Settings: SHORTENER_REPLICATOR_PASSWORD_FILE or SHORTENER_REPLICATOR_PASSWORD.
set -eu

if [ -n "${SHORTENER_REPLICATOR_PASSWORD_FILE:-}" ]; then
  password=$(cat "$SHORTENER_REPLICATOR_PASSWORD_FILE")
else
  password=${SHORTENER_REPLICATOR_PASSWORD:?SHORTENER_REPLICATOR_PASSWORD or SHORTENER_REPLICATOR_PASSWORD_FILE must be set}
fi

psql -v ON_ERROR_STOP=1 -U "${POSTGRES_USER:-postgres}" -d postgres -v password="$password" <<'SQL'
select 'create role shortener_replicator login replication nosuperuser nocreatedb nocreaterole nobypassrls connection limit 3'
where not exists (select 1 from pg_roles where rolname = 'shortener_replicator') \gexec
alter role shortener_replicator password :'password';
SQL

line="host replication shortener_replicator all scram-sha-256"
grep -qxF "$line" "$PGDATA/pg_hba.conf" || echo "$line" >>"$PGDATA/pg_hba.conf"
