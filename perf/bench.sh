#!/bin/sh
# Runs one image of the application against the compose Postgres and Keycloak under k6, and records the results in OUT_DIR:
# summary.json (what each run offered and what Prometheus saw), metadata.json (what produced them: commit, limits, workload), one k6 summary a
# run, the container's CPU and memory, and its log. A k6 summary never keeps the DPoP key or the token of the run (perf/scrub-k6-summary.sh).
#   [RATES="250 500 1000"] [DURATION=60s] [CREATES_PER_SECOND=15] [REPEAT=3] [COOLDOWN=15] [DOCKER_ARGS="..."] perf/bench.sh VARIANT IMAGE OUT_DIR [COMMAND...]
#   [RUNS="name rate duration create_share|..."] ...                         the runs written out, instead of RATES
# - RATES: one run for each rate in requests a second, of DURATION, with CREATES_PER_SECOND creates (the rest redirects). Without it or RUNS: 1,500,
#   5,000 and 10,000.
# - REPEAT: every run N times in a row, as NAME-1 to NAME-N, COOLDOWN seconds apart; `tools/run Report summary` gives the median and the range.
# - APP_CPUSET (0-1), APP_CPUS (2) and APP_MEMORY (512m) are the application's limits, for runs that vary them.
# DATASET_FILE=FILE [DATASET_HOT=H] [DATASET_HOT_SHARE=0.8] reads the codes of the file that perf/load-dataset.sh loaded, not seeds made through
# the API: it keeps the table instead of truncating it. Every read goes to a code at random, or the share of them to the first H.
# CPUs: the app 0-1 with the production memory limit, Postgres 2-5, k6 6-9. Needs Postgres, Keycloak and Prometheus (profile
# observability) running: the compose services, or containers of your own with PGPORT and PG_CONTAINER set. Needs GNU date (`%N`) and jq.
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
command -v jq >/dev/null 2>&1 || { echo "perf/bench.sh needs jq: it removes the DPoP key and the token from the k6 summaries" >&2; exit 1; }

APP_CPUSET=${APP_CPUSET:-0-1}
APP_CPUS=${APP_CPUS:-2}
APP_MEMORY=${APP_MEMORY:-512m}
DURATION=${DURATION:-60s}
CREATES_PER_SECOND=${CREATES_PER_SECOND:-15}
REPEAT=${REPEAT:-1}
COOLDOWN=${COOLDOWN:-0}
case $REPEAT in ''|*[!0-9]*|0) echo "REPEAT must be a whole number of at least 1, was '$REPEAT'" >&2; exit 2;; esac
case $COOLDOWN in ''|*[!0-9]*) echo "COOLDOWN must be a whole number of seconds, was '$COOLDOWN'" >&2; exit 2;; esac
if [ -z "${RUNS:-}" ]; then
  if [ -n "${RATES:-}" ]; then
    RUNS=
    for rate in $RATES; do
      case $rate in ''|*[!0-9]*|0) echo "RATES are whole numbers of requests a second, found '$rate'" >&2; exit 2;; esac
      share=$(awk -v creates="$CREATES_PER_SECOND" -v rate="$rate" 'BEGIN { share = creates / rate; if (share > 0.5) share = 0.5; printf "%.6g", share }')
      RUNS="${RUNS:+$RUNS|}r$rate $rate $DURATION $share"
    done
  else
    RUNS="r1500 1500 60s 0.01|r5000 5000 60s 0.003|r10000 10000 30s 0.0015"
  fi
fi
printf '%s\n' "$RUNS" | tr '|' '\n' | while read -r name rate duration share; do
  n=1
  while [ "$n" -le "$REPEAT" ]; do
    if [ "$REPEAT" -gt 1 ]; then echo "$name-$n $rate $duration $share $n"; else echo "$name $rate $duration $share 1"; fi
    n=$((n + 1))
  done
done >"$OUT/runs.txt"
HIGHEST=$(awk '{ if ($2 > top) top = $2 } END { printf "%d", top }' "$OUT/runs.txt")
if [ "$HIGHEST" -gt 5000 ]; then
  say "WARNING: $HIGHEST req/s is past what the docs call safe to run: overload can take down the network of a machine whose firewall inspects new connections (docs/INTERNALS.md#running-load-tests-safely)"
fi

say "== $VARIANT: $IMAGE $*"
docker update --cpuset-cpus 2-5 "$PG_CONTAINER" >/dev/null
docker rm -f bench-app >/dev/null 2>&1 || true

t0=$(date +%s%N)
# DOCKER_ARGS is several words on purpose.
# shellcheck disable=SC2086
docker run -d --name bench-app --network host --cpuset-cpus "$APP_CPUSET" --cpus "$APP_CPUS" --memory "$APP_MEMORY" \
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

js() { printf '%s' "$1" | sed 's/\\/\\\\/g; s/"/\\"/g'; }
DIRTY=false
[ -z "$(git -C "$ROOT" status --porcelain 2>/dev/null)" ] || DIRTY=true
DATASET_ROWS=null
[ -z "$DATASET_FILE" ] || DATASET_ROWS=$(($(wc -c <"$DATASET_FILE") / 8))
cat >"$OUT/metadata.json" <<J
{
  "variant": "$(js "$VARIANT")",
  "image": "$(js "$IMAGE")",
  "arguments": "$(js "$*")",
  "docker_args": "$(js "${DOCKER_ARGS:-}")",
  "commit": "$(git -C "$ROOT" rev-parse --short HEAD 2>/dev/null || echo unknown)",
  "dirty": $DIRTY,
  "version": "$(sed -n 's/^version = "\(.*\)"/\1/p' "$ROOT/build.gradle.kts")",
  "date": "$(date -u +%FT%TZ)",
  "host": {"kernel": "$(uname -r)", "cpus": $(nproc)},
  "limits": {"app_cpuset": "$APP_CPUSET", "app_cpus": "$APP_CPUS", "app_memory": "$APP_MEMORY", "postgres_cpuset": "2-5", "k6_cpuset": "6-9"},
  "load": {"executor": "constant-arrival-rate", "warmup": "2000 req/s for 30s", "duration": "$DURATION", "creates_per_second": $CREATES_PER_SECOND, "repeat": $REPEAT, "cooldown_seconds": $COOLDOWN, "max_vus": 3000},
  "workload": {"dataset_file": "$(js "$(basename "${DATASET_FILE:-}")")", "dataset_rows": $DATASET_ROWS, "dataset_hot": ${DATASET_HOT:-0}, "dataset_hot_share": ${DATASET_HOT_SHARE:-0.8}, "seeded_links": $SEEDS}
}
J

k6() { # name rate duration share
  say "load $1: $2 requests/s for $3, a create share of $4"
  docker run --rm -t --network host --cpuset-cpus 6-9 -v "$HERE/k6:/scripts:ro" -v "$ROOT/deploy/keycloak/dev-keys:/keys:ro" -v "$OUT:/out" \
    -e RATE="$2" -e DURATION="$3" -e CREATE_SHARE="$4" -e SEEDS="$SEEDS" -e MAX_VUS=3000 \
    ${DATASET_FILE:+-v "$DATASET_FILE:/dataset.txt:ro" -e DATASET_FILE=/dataset.txt} -e DATASET_HOT="${DATASET_HOT:-0}" -e DATASET_HOT_SHARE="${DATASET_HOT_SHARE:-0.8}" \
    grafana/k6 run --summary-trend-stats "avg,min,med,max,p(90),p(95),p(99)" --summary-export "/out/$1.k6.json" /scripts/mixed.js </dev/null 2>&1 | tee "$OUT/$1.k6.txt" || true
  "$HERE/scrub-k6-summary.sh" "$OUT/$1.k6.json"
}

sampler() { while true; do echo "$(date +%s) $(docker stats --no-stream --format '{{.CPUPerc}} {{.MemUsage}}' bench-app 2>/dev/null)"; done; }
sampler >"$OUT/docker-stats.txt" &
SAMPLER=$!

say "warming up for 30s"
k6 warmup 2000 30s 0.005
echo "{\"variant\":\"$VARIANT\",\"image\":\"$IMAGE\",\"ready_ms\":$ready,\"idle_mem\":\"$idle_mem\",\"started\":\"$started\",\"runs\":[" >"$OUT/summary.json"
first=1
exec 3<"$OUT/runs.txt"
while read -r name rate duration share repeat <&3; do
  start=$(date +%s)
  k6 "$name" "$rate" "$duration" "$share"
  end=$(date +%s); sleep $((7 + COOLDOWN))
  dur=$((end - start + 7))
  server=$("$ROOT/tools/run" Report server "$PROM" "$end" "$dur")
  [ $first = 1 ] || echo "," >>"$OUT/summary.json"; first=0
  say "server side for $name (from Prometheus): $server; container now: $(docker stats --no-stream --format '{{.CPUPerc}} cpu, {{.MemUsage}}' bench-app)"
  cat >>"$OUT/summary.json" <<J
{"name":"$name","rate":$rate,"duration":"$duration","create_share":$share,"repeat":$repeat,"start":$start,"end":$end,"server":$server}
J
done
exec 3<&-
echo "]}" >>"$OUT/summary.json"

kill $SAMPLER 2>/dev/null || true
docker logs bench-app >"$OUT/app.log" 2>&1
docker rm -f bench-app >/dev/null
say "$VARIANT finished; app log has $(grep -c OutOfMemoryError "$OUT/app.log" || true) OutOfMemoryError lines; results in $OUT"
"$ROOT/tools/run" Report json "$OUT/summary.json"
"$ROOT/tools/run" Report json "$OUT/metadata.json"
