#!/bin/sh
# Checks every endpoint of a running application with DPoP-bound tokens from the local Keycloak. Starts the Smoke tool.
#
#   [MGMT=http://localhost:8081] perf/smoke.sh [BASE_URL]
set -eu
exec "$(dirname "$0")/../tools/run" Smoke "$@"
