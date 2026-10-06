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

psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d postgres -v password="$password" <<'SQL'
SELECT 'CREATE ROLE shortener_replicator LOGIN REPLICATION NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS CONNECTION LIMIT 3'
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'shortener_replicator') \gexec
ALTER ROLE shortener_replicator PASSWORD :'password';
SQL

line="host replication shortener_replicator all scram-sha-256"
grep -qxF "$line" "$PGDATA/pg_hba.conf" || echo "$line" >>"$PGDATA/pg_hba.conf"
