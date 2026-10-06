#!/bin/sh
# Exercises every endpoint of a running application with DPoP-bound tokens from the local Keycloak, as the demo, other and
# admin clients, and checks the status each should answer. The work is the Smoke tool (tools/), which says how to use it: this only
# starts it. Meant for a native image, where what works on the JVM can still fail for want of reflection metadata.
#
#   [MGMT=http://localhost:8081] perf/smoke.sh [BASE_URL]
set -eu
exec "$(dirname "$0")/../tools/run" Smoke "$@"
