#!/bin/sh
# Gives Keycloak a database of its own in the same Postgres, and a role that is the only one that can use it. It runs once, when
# the container first initialises its data directory, after the roles of bootstrap.sql. The password comes from
# SHORTENER_KEYCLOAK_PASSWORD or, when set, from the file named by SHORTENER_KEYCLOAK_PASSWORD_FILE (a Docker secret).
#
# It can be run again, and on an existing database, as the superuser:
#   docker exec <postgres container> sh /docker-entrypoint-initdb.d/30-keycloak-database.sh
# (POSTGRES_USER is set by the image's entrypoint when this runs at initialisation, and `docker exec` does not have it: the superuser defaults to postgres.)
# shortener_app cannot connect to this database, and this role cannot connect to the shortener one.
set -eu

if [ -n "${SHORTENER_KEYCLOAK_PASSWORD_FILE:-}" ]; then
  password=$(cat "$SHORTENER_KEYCLOAK_PASSWORD_FILE")
else
  password=${SHORTENER_KEYCLOAK_PASSWORD:?SHORTENER_KEYCLOAK_PASSWORD or SHORTENER_KEYCLOAK_PASSWORD_FILE must be set}
fi

psql -v ON_ERROR_STOP=1 -U "${POSTGRES_USER:-postgres}" -d postgres -v password="$password" <<'SQL'
select 'create role keycloak login nosuperuser nocreatedb nocreaterole noreplication nobypassrls'
where not exists (select 1 from pg_roles where rolname = 'keycloak') \gexec
alter role keycloak password :'password';
select 'create database keycloak owner keycloak'
where not exists (select 1 from pg_database where datname = 'keycloak') \gexec
revoke all on database keycloak from public;
grant connect on database keycloak to keycloak;
SQL
