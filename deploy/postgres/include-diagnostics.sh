#!/bin/sh
# Makes the server read diagnostics.conf. The image runs this once, when it initialises the data directory and before
# the server starts for good, which is what shared_preload_libraries needs. The file is mounted at
# /etc/shortener/diagnostics.conf unless SHORTENER_DIAGNOSTICS_CONF says otherwise.
set -eu
echo "include '${SHORTENER_DIAGNOSTICS_CONF:-/etc/shortener/diagnostics.conf}'" >> "$PGDATA/postgresql.conf"
