#!/bin/sh
# Gives Keycloak a database of its own in the same Postgres, and a role that is the only one that can use it. It runs once,
# when the container first initialises its data directory, after the roles of bootstrap.sql. The password comes from
# SHORTENER_KEYCLOAK_PASSWORD or, when set, from the file named by SHORTENER_KEYCLOAK_PASSWORD_FILE (a Docker secret).
#
# It can be run again, and on an existing database, as the superuser:
#   docker exec <postgres container> sh /docker-entrypoint-initdb.d/30-keycloak-database.sh
# Nothing in it touches the shortener database: shortener_app cannot connect to this one, and this role cannot connect to
# that.
set -eu

if [ -n "${SHORTENER_KEYCLOAK_PASSWORD_FILE:-}" ]; then
  password=$(cat "$SHORTENER_KEYCLOAK_PASSWORD_FILE")
else
  password=${SHORTENER_KEYCLOAK_PASSWORD:?SHORTENER_KEYCLOAK_PASSWORD or SHORTENER_KEYCLOAK_PASSWORD_FILE must be set}
fi

psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d postgres -v password="$password" <<'SQL'
SELECT 'CREATE ROLE keycloak LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS'
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'keycloak') \gexec
ALTER ROLE keycloak PASSWORD :'password';
SELECT 'CREATE DATABASE keycloak OWNER keycloak'
WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = 'keycloak') \gexec
REVOKE ALL ON DATABASE keycloak FROM PUBLIC;
GRANT CONNECT ON DATABASE keycloak TO keycloak;
SQL
