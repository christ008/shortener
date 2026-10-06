#!/bin/sh
# Starts Keycloak, after reading the files named by KC_DB_PASSWORD_FILE and KC_BOOTSTRAP_ADMIN_PASSWORD_FILE (Docker secrets)
# into KC_DB_PASSWORD and KC_BOOTSTRAP_ADMIN_PASSWORD. A value already in the environment is replaced by the file's content.
set -eu

read_secret() { # NAME: sets NAME to the content of the file that NAME_FILE names, when it is set, and forgets NAME_FILE
  eval "file=\${$1_FILE:-}"
  [ -n "$file" ] || return 0
  [ -r "$file" ] || { echo "entrypoint: $1_FILE names $file, which cannot be read" >&2; exit 1; }
  eval "$1=\$(cat \"\$file\")"
  export "${1?}"
  unset "$1_FILE"
}

read_secret KC_DB_PASSWORD
read_secret KC_BOOTSTRAP_ADMIN_PASSWORD
exec /opt/keycloak/bin/kc.sh "$@"
