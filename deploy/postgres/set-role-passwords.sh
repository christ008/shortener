#!/bin/sh
# Gives the Shortener roles their passwords. It runs once, when the container first initialises its data directory,
# after bootstrap.sql. Each password comes from SHORTENER_<ROLE>_PASSWORD or, when set, from the file named by
# SHORTENER_<ROLE>_PASSWORD_FILE (a Docker secret), for ROLE in APP, MIGRATOR and EXPORTER. Unset passwords stop it
# rather than default to something guessable. The compose file for development passes public throwaway values.
set -eu

password() { # ROLE
  file=$(eval "echo \"\${SHORTENER_$1_PASSWORD_FILE:-}\"")
  if [ -n "$file" ]; then
    cat "$file"
  else
    eval "echo \"\${SHORTENER_$1_PASSWORD:?SHORTENER_$1_PASSWORD or SHORTENER_$1_PASSWORD_FILE must be set}\""
  fi
}

app=$(password APP)
migrator=$(password MIGRATOR)
exporter=$(password EXPORTER)

psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" \
  -v app="$app" -v migrator="$migrator" -v exporter="$exporter" <<'SQL'
ALTER ROLE shortener_app PASSWORD :'app';
ALTER ROLE shortener_migrator PASSWORD :'migrator';
ALTER ROLE shortener_exporter PASSWORD :'exporter';
SQL
