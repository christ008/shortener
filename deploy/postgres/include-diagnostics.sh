#!/bin/sh
# Makes the server read diagnostics.conf: appends an `include` to postgresql.conf when the image initialises the data directory.
# The file is mounted at /etc/shortener/diagnostics.conf unless SHORTENER_DIAGNOSTICS_CONF says otherwise.
set -eu
echo "include '${SHORTENER_DIAGNOSTICS_CONF:-/etc/shortener/diagnostics.conf}'" >> "$PGDATA/postgresql.conf"
