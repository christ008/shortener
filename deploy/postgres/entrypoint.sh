#!/bin/sh
# Entrypoint of the image: makes the pgBackRest repository (PGBACKREST_REPO_PATH, default /var/lib/pgbackrest) writable by the
# postgres user, then runs the stock entrypoint with its arguments.
set -eu
repo=${PGBACKREST_REPO_PATH:-/var/lib/pgbackrest}
mkdir -p "$repo/log"
chown postgres:postgres "$repo" "$repo/log"
chmod 0750 "$repo"
exec docker-entrypoint.sh "$@"
