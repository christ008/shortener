#!/bin/sh
# Runs one image under a steady load and records what the container did.
#   [DOCKER_ARGS="..."] perf/profile.sh NAME IMAGE RATE DURATION CREATE_SHARE MAX_VUS [app arguments...]
# Output goes to perf/results/profiles/NAME: app.log (with GC lines when -XX:+PrintGC is passed), k6 results, stats.txt and
# anything the app writes to /out, such as a JFR recording or a heap dump. Analyse it with `tools/run Report gc` and
# `tools/run Report hprof`.
# The k6 summary is cleaned of the DPoP key and the token by perf/scrub-k6-summary.sh (needs jq).
# Needs the compose Postgres and Keycloak. A native image built with -PnativeProfiling can record JFR and dump the heap:
#   perf/profile.sh native-5000 shortener:0.14.0-profiling 5000 60s 0.003 3000 -XX:+PrintGC -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/out/oom.hprof
set -u
NAME=$1; IMAGE=$2; RATE=$3; DUR=$4; SHARE=$5; MAXVUS=$6; shift 6
ROOT=$(cd "$(dirname "$0")/.." && pwd)
PGPASS=$(sed -n "s/^DEV_POSTGRES_PASSWORD='\{0,1\}\([^']*\)'\{0,1\}$/\1/p" "$ROOT/.env" 2>/dev/null)
[ -n "$PGPASS" ] || { echo "no Postgres password in $ROOT/.env: run deploy/keycloak/dev-setup first" >&2; exit 1; }
OUT=$ROOT/perf/results/profiles/$NAME; rm -rf "$OUT"; mkdir -p "$OUT"; chmod 777 "$OUT"
PGPORT=$(docker compose -f "$ROOT/compose.yaml" port postgres 5432 | cut -d: -f2)
docker rm -f prof-app >/dev/null 2>&1
docker update --cpuset-cpus 2-5 "$(docker compose -f "$ROOT/compose.yaml" ps -q postgres)" >/dev/null
# DOCKER_ARGS is several words on purpose.
# shellcheck disable=SC2086
docker run -d --name prof-app --network host --cpuset-cpus 0-1 --cpus 2 --memory 512m -v "$OUT:/out" \
  -e SPRING_DATASOURCE_URL="jdbc:postgresql://localhost:$PGPORT/mydatabase" -e SPRING_DATASOURCE_USERNAME=myuser -e "SPRING_DATASOURCE_PASSWORD=$PGPASS" \
  -e SHORTENER_SECURITY_RATELIMIT_PERIP_CAPACITY=1000000000 -e SHORTENER_SECURITY_RATELIMIT_PERCLIENT_CAPACITY=1000000000 \
  ${DOCKER_ARGS:-} "$IMAGE" "$@" >/dev/null
until curl -sf -m2 -o /dev/null localhost:8081/actuator/health/readiness; do
  [ -n "$(docker ps -q -f name=prof-app)" ] || { echo "app stopped"; docker logs prof-app 2>&1 | tail; exit 1; }; sleep 0.2; done
docker exec "$(docker compose -f "$ROOT/compose.yaml" ps -q postgres)" psql -q -U myuser -d mydatabase -c 'TRUNCATE short_link' >/dev/null 2>&1
( while true; do echo "$(date +%s) $(docker stats --no-stream --format '{{.CPUPerc}} {{.MemUsage}}' prof-app 2>/dev/null)"; sleep 2; done ) >"$OUT/stats.txt" &
SAMPLER=$!
docker run --rm --network host --cpuset-cpus 6-9 -v "$ROOT/perf/k6:/scripts:ro" -v "$ROOT/deploy/keycloak/dev-keys:/keys:ro" -v "$OUT:/out" \
  -e RATE=2000 -e DURATION=20s -e CREATE_SHARE=0.005 -e SEEDS=300 -e MAX_VUS="$MAXVUS" grafana/k6 run --quiet /scripts/mixed.js >"$OUT/warmup.txt" 2>&1 </dev/null
docker run --rm --network host --cpuset-cpus 6-9 -v "$ROOT/perf/k6:/scripts:ro" -v "$ROOT/deploy/keycloak/dev-keys:/keys:ro" -v "$OUT:/out" \
  -e RATE="$RATE" -e DURATION="$DUR" -e CREATE_SHARE="$SHARE" -e SEEDS=300 -e MAX_VUS="$MAXVUS" grafana/k6 run --quiet --summary-export /out/k6.json /scripts/mixed.js >"$OUT/k6.txt" 2>&1 </dev/null
"$ROOT/perf/scrub-k6-summary.sh" "$OUT/k6.json"
kill $SAMPLER 2>/dev/null
docker stop -t 40 prof-app >/dev/null 2>&1
docker logs prof-app >"$OUT/app.log" 2>&1; docker rm -f prof-app >/dev/null
"$ROOT/tools/run" Report profile "$OUT"
