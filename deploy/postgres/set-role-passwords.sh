#!/bin/sh
# Gives the Shortener roles the passwords in SHORTENER_APP_PASSWORD, SHORTENER_MIGRATOR_PASSWORD and
# SHORTENER_EXPORTER_PASSWORD. It runs once, when the container first initialises its data directory, after
# bootstrap.sql, and is for throwaway databases (compose, kind) whose passwords are public on purpose. A real
# deployment sets them from its secret store. Unset variables stop it rather than default to something guessable.
set -eu
: "${SHORTENER_APP_PASSWORD:?}" "${SHORTENER_MIGRATOR_PASSWORD:?}" "${SHORTENER_EXPORTER_PASSWORD:?}"

psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" \
  -v app="$SHORTENER_APP_PASSWORD" -v migrator="$SHORTENER_MIGRATOR_PASSWORD" -v exporter="$SHORTENER_EXPORTER_PASSWORD" <<'SQL'
ALTER ROLE shortener_app PASSWORD :'app';
ALTER ROLE shortener_migrator PASSWORD :'migrator';
ALTER ROLE shortener_exporter PASSWORD :'exporter';
SQL
