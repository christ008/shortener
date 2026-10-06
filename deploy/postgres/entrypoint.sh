#!/bin/sh
# The entrypoint of the image, which starts as root like the stock one: it makes the repository of pgBackRest writable by the
# postgres user, since a volume is created owned by root, and hands over to the stock entrypoint.
set -eu
repo=${PGBACKREST_REPO_PATH:-/var/lib/pgbackrest}
mkdir -p "$repo/log"
chown postgres:postgres "$repo" "$repo/log"
chmod 0750 "$repo"
exec docker-entrypoint.sh "$@"
