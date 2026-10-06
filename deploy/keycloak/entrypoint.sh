#!/bin/bash
# Starts Keycloak, after reading the two settings that are secrets from the files named by KC_DB_PASSWORD_FILE and
# KC_BOOTSTRAP_ADMIN_PASSWORD_FILE (Docker secrets). Keycloak has no such convention of its own. A name that is already set
# in the environment is replaced by the file's content, so a secret cannot be left in a variable by mistake.
set -eu
for name in KC_DB_PASSWORD KC_BOOTSTRAP_ADMIN_PASSWORD; do
  file_variable="${name}_FILE"
  file="${!file_variable:-}"
  if [ -n "$file" ]; then
    [ -r "$file" ] || { echo "entrypoint: $file_variable names $file, which cannot be read" >&2; exit 1; }
    export "$name=$(cat "$file")"
    unset "$file_variable"
  fi
done
exec /opt/keycloak/bin/kc.sh "$@"
