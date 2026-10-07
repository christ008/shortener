#!/bin/sh
# Runs one image of the application against the compose Postgres and Keycloak under k6, and records the results in OUT_DIR.
#   [RUNS="name rate duration create_share|..."] [DOCKER_ARGS="..."] perf/bench.sh VARIANT IMAGE OUT_DIR [COMMAND...]
# DATASET_FILE=FILE [DATASET_HOT=H] [DATASET_HOT_SHARE=0.8] reads the codes of the file that perf/load-dataset.sh loaded, not seeds made through
# the API: it keeps the table instead of truncating it. Every read goes to a code at random, or the share of them to the first H.
# CPUs: the app 0-1 with the production memory limit, Postgres 2-5, k6 6-9. Needs Postgres, Keycloak and Prometheus (profile
# observability) running: the compose services, or containers of your own with PGPORT and PG_CONTAINER set. Needs GNU date (`%N`).
set -eu

VARIANT=$1
IMAGE=$2
OUT_ARGUMENT=$3
shift 3
mkdir -p "$OUT_ARGUMENT"
OUT=$(cd "$OUT_ARGUMENT" && pwd)
chmod 777 "$OUT"
HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/.." && pwd)
PGPASS=$(sed -n "s/^DEV_POSTGRES_PASSWORD='\{0,1\}\([^']*\)'\{0,1\}$/\1/p" "$ROOT/.env" 2>/dev/null)
[ -n "$PGPASS" ] || { echo "no Postgres password in $ROOT/.env: run deploy/keycloak/dev-setup first" >&2; exit 1; }
PGPORT=${PGPORT:-$(docker compose -f "$ROOT/compose.yaml" port postgres 5432 | cut -d: -f2)}
PG_CONTAINER=${PG_CONTAINER:-$(docker compose -f "$ROOT/compose.yaml" ps -q postgres)}
PROM=http://localhost:9090
say() { printf '\n\033[1m[%s] %s\033[0m\n' "$(date +%T)" "$*"; }

say "== $VARIANT: $IMAGE $*"
docker update --cpuset-cpus 2-5 "$PG_CONTAINER" >/dev/null
docker rm -f bench-app >/dev/null 2>&1 || true

t0=$(date +%s%N)
# DOCKER_ARGS is several words on purpose.
# shellcheck disable=SC2086
docker run -d --name bench-app --network host --cpuset-cpus 0-1 --cpus 2 --memory 512m \
  -e SPRING_DATASOURCE_URL="jdbc:postgresql://localhost:$PGPORT/mydatabase" \
  -e SPRING_DATASOURCE_USERNAME=myuser -e "SPRING_DATASOURCE_PASSWORD=$PGPASS" \
  -e SHORTENER_SECURITY_RATELIMIT_PERIP_CAPACITY=1000000000 -e SHORTENER_SECURITY_RATELIMIT_PERCLIENT_CAPACITY=1000000000 \
  ${DOCKER_ARGS:-} "$IMAGE" "$@" >/dev/null
until curl -sf -m 2 -o /dev/null localhost:8081/actuator/health/readiness; do
  [ -n "$(docker ps -q -f name=bench-app)" ] || { echo "the application container stopped:"; docker logs bench-app 2>&1 | tail -20; exit 1; }
  sleep 0.05
done
ready=$((($(date +%s%N) - t0) / 1000000))
sleep 5
idle_mem=$(docker stats --no-stream --format '{{.MemUsage}}' bench-app | cut -d/ -f1 | xargs)
started=$(docker logs bench-app 2>&1 | grep -oE "Started .* in [0-9.]+ seconds" | head -1)
say "up: ready after ${ready} ms from docker run, ${started}, memory at rest ${idle_mem}"
DATASET_FILE=${DATASET_FILE:-}
if [ -n "$DATASET_FILE" ]; then
  [ -r "$DATASET_FILE" ] || { echo "cannot read $DATASET_FILE" >&2; exit 1; }
  DATASET_FILE=$(cd "$(dirname "$DATASET_FILE")" && pwd)/$(basename "$DATASET_FILE")
  say "dataset $DATASET_FILE: short_link is kept, with $(docker exec "$PG_CONTAINER" psql -At -U myuser -d mydatabase -c 'SELECT count(*) FROM short_link') rows"
else
  docker exec "$PG_CONTAINER" psql -q -U myuser -d mydatabase -c 'TRUNCATE short_link' >/dev/null 2>&1 || true
fi

SEEDS=300
[ -z "$DATASET_FILE" ] || SEEDS=0

k6() { # name rate duration share
  say "load $1: $2 requests/s for $3, a create share of $4"
  docker run --rm -t --network host --cpuset-cpus 6-9 -v "$HERE/k6:/scripts:ro" -v "$ROOT/deploy/keycloak/dev-keys:/keys:ro" -v "$OUT:/out" \
    -e RATE="$2" -e DURATION="$3" -e CREATE_SHARE="$4" -e SEEDS="$SEEDS" -e MAX_VUS=3000 \
    ${DATASET_FILE:+-v "$DATASET_FILE:/dataset.txt:ro" -e DATASET_FILE=/dataset.txt} -e DATASET_HOT="${DATASET_HOT:-0}" -e DATASET_HOT_SHARE="${DATASET_HOT_SHARE:-0.8}" \
    grafana/k6 run --summary-trend-stats "avg,min,med,max,p(90),p(95),p(99)" --summary-export "/out/$1.k6.json" /scripts/mixed.js </dev/null 2>&1 | tee "$OUT/$1.k6.txt" || true
}

sampler() { while true; do echo "$(date +%s) $(docker stats --no-stream --format '{{.CPUPerc}} {{.MemUsage}}' bench-app 2>/dev/null)"; done; }
sampler >"$OUT/docker-stats.txt" &
SAMPLER=$!

say "warming up for 30s"
k6 warmup 2000 30s 0.005
echo "{\"variant\":\"$VARIANT\",\"image\":\"$IMAGE\",\"ready_ms\":$ready,\"idle_mem\":\"$idle_mem\",\"started\":\"$started\",\"runs\":[" >"$OUT/summary.json"
first=1
RUNS=${RUNS:-"r1500 1500 60s 0.01|r5000 5000 60s 0.003|r10000 10000 30s 0.0015"}
printf '%s\n' "$RUNS" | tr '|' '\n' >"$OUT/runs.txt"
exec 3<"$OUT/runs.txt"
while read -r name rate duration share <&3; do
  start=$(date +%s)
  k6 "$name" "$rate" "$duration" "$share"
  end=$(date +%s); sleep 7
  dur=$((end - start + 7))
  server=$("$ROOT/tools/run" Report server "$PROM" "$end" "$dur")
  [ $first = 1 ] || echo "," >>"$OUT/summary.json"; first=0
  say "server side for $name (from Prometheus): $server; container now: $(docker stats --no-stream --format '{{.CPUPerc}} cpu, {{.MemUsage}}' bench-app)"
  cat >>"$OUT/summary.json" <<J
{"name":"$name","rate":$rate,"duration":"$duration","create_share":$share,"start":$start,"end":$end,"server":$server}
J
done
exec 3<&-
echo "]}" >>"$OUT/summary.json"

kill $SAMPLER 2>/dev/null || true
docker logs bench-app >"$OUT/app.log" 2>&1
docker rm -f bench-app >/dev/null
say "$VARIANT finished; app log has $(grep -c OutOfMemoryError "$OUT/app.log" || true) OutOfMemoryError lines; results in $OUT"
"$ROOT/tools/run" Report json "$OUT/summary.json"
