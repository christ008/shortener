#!/bin/sh
# Lets a replica connect: the role shortener_replicator, which can only replicate, and the line of pg_hba.conf that admits it.
# It runs once, when the container first initialises its data directory, after the roles of bootstrap.sql. The password is the
# file named by SHORTENER_REPLICATOR_PASSWORD_FILE, or SHORTENER_REPLICATOR_PASSWORD.
#
# On a database that exists already, run it by hand as the superuser and reload the configuration:
#   docker exec <postgres container> sh /docker-entrypoint-initdb.d/35-replication.sh
#   docker exec <postgres container> psql -U postgres -c 'SELECT pg_reload_conf()'
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
