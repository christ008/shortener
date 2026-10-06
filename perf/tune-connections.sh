#!/usr/bin/env bash
# Runs one native image at 5,000 requests a second (or the RUNS given) with 3,000 clients for several Tomcat connection limits, to see
# which limit keeps memory bounded and latency flat. The first value is the default, which has no practical limit.
#   [IMAGE=shortener:0.15.0] [RUNS="name rate duration create_share"] perf/tune-connections.sh [OUT_DIR] [LIMIT...]
# Watch it in Grafana (http://localhost:3000/d/shortener/shortener) with the compose observability profile running.
set -euo pipefail
cd "$(dirname "$0")/.."
OUT=${1:-perf/results/connections-$(date +%H%M)}; shift || true
LIMITS=("${@:-8192 1000 500 250}")
IFS=' ' read -ra LIMITS <<<"${LIMITS[*]}"
IMAGE=${IMAGE:-shortener:0.15.0}

for limit in "${LIMITS[@]}"; do
  RUNS="${RUNS:-r5000 5000 60s 0.003}" DOCKER_ARGS="-e SERVER_TOMCAT_MAXCONNECTIONS=$limit -e SERVER_TOMCAT_ACCEPTCOUNT=100" \
    perf/bench.sh "maxconn-$limit" "$IMAGE" "$OUT/maxconn-$limit" -XX:+PrintGC
done

printf '\n\033[1m== comparison (%s)\033[0m\n\n' "$OUT"
tools/run Report summary "$OUT"
for limit in "${LIMITS[@]}"; do
  printf '\n-- GC with max-connections=%s\n' "$limit"
  tools/run Report gc "$OUT/maxconn-$limit/app.log" | head -4
done
