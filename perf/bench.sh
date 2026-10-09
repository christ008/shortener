#!/bin/sh
# Runs one image of the application against the compose Postgres and Keycloak under k6, and records the results in OUT_DIR:
# summary.json (what each run offered and what Prometheus saw), metadata.json (what produced them: commit, limits, workload, scenario), one k6
# summary a run, the CPU and memory of the containers, and their logs. A k6 summary never keeps the DPoP key or the token of the run
# (perf/scrub-k6-summary.sh).
#   perf/bench.sh [--scenario app|edge|full] VARIANT IMAGE OUT_DIR [COMMAND...]
#
# Scenarios, from the least to the most like the stack:
#   app    the application alone: k6 straight to it over HTTP on the host network, 2 CPUs and 512 MiB, each role on cores of its own. The default.
#   edge   the stack's edge in front of it: deploy/edge/nginx.conf as it is, in a container with 1 CPU and 128 MiB, TLS (HTTP/2), k6 over HTTPS.
#   full   edge, and the limits of the stack on the rest: the application on a CPU quota alone (as Swarm limits), Postgres at 2 CPUs and 1 GiB.
# A scenario only sets the defaults of the variables below, and a variable given still wins.
#
# What a run offers (any scenario):
#   RATES="250 500 1000"  one run for each rate in requests a second, of DURATION (60s), with CREATES_PER_SECOND (15) creates and the rest redirects,
#                         or CREATE_SHARE of them (1 is creates only). Without RATES or RUNS: 1,500, 5,000 and 10,000.
#   RUNS="name rate duration create_share|..."   the runs written out, instead of RATES.
#   REPEAT=3 COOLDOWN=15  every run 3 times in a row, as NAME-1 to NAME-3, 15 seconds apart; `tools/run Report summary` gives the median and the range.
#   WARMUP_RATE (2000) and WARMUP_DURATION (30s) are the warm-up before the runs.
#   DATASET_FILE=FILE [DATASET_HOT=H] [DATASET_HOT_SHARE=0.8]   reads the codes of the file that perf/load-dataset.sh loaded, not seeds made through
#                         the API, and keeps the table instead of truncating it. Every read goes to a code at random, or the share of them to the first H.
#   RATE_LIMITS=on        keeps the application's own request limits, which a run lifts by default.
# What it runs on:
#   APP_CPUSET (0,1), APP_CPUS (2), APP_MEMORY (512m)   the application's CPUs, quota and memory; APP_CPUSET=none leaves it on any CPU with the quota alone.
#   PG_CPUSET (2,3,8,9; 3,9 with the edge), K6_CPUSET (4,5,10,11), EDGE_CPUSET (2)   the CPUs of the others. On a CPU whose thread N shares a core with
#                         thread N+6 (6 cores, 12 threads) these give each role physical cores of its own and leave 6 and 7 idle; the run says so when two share one.
#   PG_CPUS, PG_MEMORY    limit the Postgres container for the run (2 and 1g); it is given all the CPUs and 64g afterwards, because Docker cannot remove a limit.
#   EDGE_CONF             the nginx configuration of the edge.
#   DOCKER_ARGS           more arguments for the application's `docker run`.
# Docker stats of the load generator go to docker-stats-k6.txt (a generator at its CPU limit is the ceiling, not the application), those of the edge to docker-stats-edge.txt, and the count of its answers by status to edge-status.txt.
# Needs Postgres, Keycloak and Prometheus (profile observability) running: the compose services, or containers of your own with PGPORT and PG_CONTAINER
# set. Needs GNU date (`%N`) and jq.
set -eu

SCENARIO=app
while [ $# -gt 0 ]; do
  case $1 in
    --scenario) SCENARIO=${2:?--scenario needs app, edge or full}; shift 2;;
    --scenario=*) SCENARIO=${1#*=}; shift;;
    -h|--help) sed -n '2,/^set -eu/p' "$0" | sed '$d' | sed 's/^# \{0,1\}//'; exit 0;;
    *) break;;
  esac
done
[ $# -ge 3 ] || { echo "usage: perf/bench.sh [--scenario app|edge|full] VARIANT IMAGE OUT_DIR [COMMAND...]   (--help says more)" >&2; exit 2; }
case $SCENARIO in
  app) ;;
  edge) EDGE=${EDGE:-1};;
  full) EDGE=${EDGE:-1}; APP_CPUSET=${APP_CPUSET:-none}; PG_CPUS=${PG_CPUS:-2}; PG_MEMORY=${PG_MEMORY:-1g};;
  *) echo "the scenario is app, edge or full, not '$SCENARIO'" >&2; exit 2;;
esac

VARIANT=$1
IMAGE=$2
OUT_ARGUMENT=$3
shift 3
mkdir -p "$OUT_ARGUMENT"
OUT=$(cd "$OUT_ARGUMENT" && pwd)
chmod 777 "$OUT"
HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/.." && pwd)
PGPASS=$(sed -n "s/^DEV_POSTGRES_PASSWORD='\{0,1\}\([^']*\)'\{0,1\}$/\1/p" "$ROOT/.env" 2>/dev/null || true)
[ -n "$PGPASS" ] || { echo "no Postgres password in $ROOT/.env: run deploy/keycloak/dev-setup first" >&2; exit 1; }
PGPORT=${PGPORT:-$(docker compose -f "$ROOT/compose.yaml" port postgres 5432 | cut -d: -f2)}
PG_CONTAINER=${PG_CONTAINER:-$(docker compose -f "$ROOT/compose.yaml" ps -q postgres)}
[ -n "$PG_CONTAINER" ] || { echo "no Postgres container: start the compose services (docker compose --profile observability up -d postgres keycloak prometheus), or set PGPORT and PG_CONTAINER" >&2; exit 1; }
PROM=http://localhost:9090
say() { printf '\n\033[1m[%s] %s\033[0m\n' "$(date +%T)" "$*"; }
case $(docker info --format '{{.OperatingSystem}}' 2>/dev/null) in
  *"Docker Desktop"*) echo "the Docker daemon is Docker Desktop's, whose host network and cores are a virtual machine's, not this machine's: use the host daemon (DOCKER_CONTEXT=default)" >&2; exit 1;;
esac
command -v jq >/dev/null 2>&1 || { echo "perf/bench.sh needs jq: it removes the DPoP key and the token from the k6 summaries" >&2; exit 1; }

APP_CPUSET=${APP_CPUSET:-0,1}
EDGE=${EDGE:-0}
EDGE_CPUSET=${EDGE_CPUSET:-2}
EDGE_CONF=${EDGE_CONF:-$ROOT/deploy/edge/nginx.conf}
WARMUP_RATE=${WARMUP_RATE:-2000}
WARMUP_DURATION=${WARMUP_DURATION:-30s}
if [ "$EDGE" = 1 ]; then PG_CPUSET=${PG_CPUSET:-3,9}; else PG_CPUSET=${PG_CPUSET:-2,3,8,9}; fi
K6_CPUSET=${K6_CPUSET:-4,5,10,11}
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
      [ -z "${CREATE_SHARE:-}" ] || share=$CREATE_SHARE
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
echo '{}' >"$OUT/.preflight.json"
"$ROOT/tools/run" Report json "$OUT/.preflight.json"
rm -f "$OUT/.preflight.json"
cpus_of() { # a cpuset such as 0,1 or 2-5,8 as one CPU a word
  for part in $(echo "$1" | tr ',' ' '); do
    case $part in *-*) seq "${part%-*}" "${part#*-}";; *) echo "$part";; esac
  done
}
SHARED=$(for role in APP PG K6 $([ "$EDGE" = 1 ] && echo EDGE); do
  eval "set=\$${role}_CPUSET"
  [ "$set" != none ] || continue
  for cpu in $(cpus_of "$set"); do
    core=$(cat "/sys/devices/system/cpu/cpu$cpu/topology/core_id" 2>/dev/null) || continue
    echo "$core $role"
  done
done | sort -u | awk '{ roles[$1] = roles[$1] " " $2; n[$1]++ } END { for (c in n) if (n[c] > 1) print "core " c ":" roles[c] }' | sort | tr '\n' ';')
[ -z "$SHARED" ] || say "WARNING: roles share a physical core ($SHARED): their threads compete, so the figures are lower than the hardware can do. Set APP_CPUSET, PG_CPUSET and K6_CPUSET"
LOAD=$(cut -d' ' -f1 /proc/loadavg 2>/dev/null || echo 0)
awk -v average="$LOAD" 'BEGIN { exit !(average > 1.5) }' && say "WARNING: the machine is busy (load average $LOAD): close what you can, a browser or Docker Desktop's VM take CPU from the run" || true
HIGHEST=$(awk '{ if ($2 > top) top = $2 } END { printf "%d", top }' "$OUT/runs.txt")
if [ "$HIGHEST" -gt 5000 ]; then
  say "WARNING: $HIGHEST req/s is past what the docs call safe to run: overload can take down the network of a machine whose firewall inspects new connections (docs/INTERNALS.md#running-load-tests-safely)"
fi

say "== $VARIANT: $IMAGE $*"
docker update --cpuset-cpus "$PG_CPUSET" "$PG_CONTAINER" >/dev/null
PG_LIMITED=
if [ -n "${PG_CPUS:-}${PG_MEMORY:-}" ]; then
  docker update ${PG_CPUS:+--cpus "$PG_CPUS"} ${PG_MEMORY:+--memory "$PG_MEMORY" --memory-swap "$PG_MEMORY"} "$PG_CONTAINER" >/dev/null
  PG_LIMITED=1
  # Lowering the memory limit below the page cache the container holds makes the kernel reclaim the difference, and Postgres stalls
  # for seconds meanwhile: the first reads of the run answered 504.
  [ -z "${PG_MEMORY:-}" ] || sleep 30
fi
docker rm -f bench-app bench-edge >/dev/null 2>&1 || true

RATE_LIMIT_ARGS="-e SHORTENER_SECURITY_RATELIMIT_PERIP_CAPACITY=1000000000 -e SHORTENER_SECURITY_RATELIMIT_PERCLIENT_CAPACITY=1000000000"
[ "${RATE_LIMITS:-}" != on ] || RATE_LIMIT_ARGS=
BASE_URL=http://localhost:8080
K6_TLS=
APP_CPUSET_ARGUMENT="--cpuset-cpus $APP_CPUSET"
[ "$APP_CPUSET" != none ] || APP_CPUSET_ARGUMENT=
if [ "$EDGE" = 1 ]; then
  docker network rm bench-net >/dev/null 2>&1 || true
  docker network create bench-net >/dev/null
  APP_NETWORK="--network bench-net --network-alias shortener --add-host host.docker.internal:host-gateway -p 127.0.0.1:8081:8081"
  DB_HOST=host.docker.internal
  EDGE_TLS=$(mktemp -d)
  chmod 755 "$EDGE_TLS"
  openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:prime256v1 -nodes -days 2 -subj "/CN=edge" -keyout "$EDGE_TLS/tls_key" -out "$EDGE_TLS/tls_cert" 2>/dev/null
  chmod 644 "$EDGE_TLS/tls_key" "$EDGE_TLS/tls_cert"
  K6_TLS=--insecure-skip-tls-verify
else
  APP_NETWORK="--network host"
  DB_HOST=localhost
fi

t0=$(date +%s%N)
# DOCKER_ARGS and APP_NETWORK are several words on purpose.
# shellcheck disable=SC2086
docker run -d --name bench-app $APP_NETWORK $APP_CPUSET_ARGUMENT --cpus "$APP_CPUS" --memory "$APP_MEMORY" \
  -e SPRING_DATASOURCE_URL="jdbc:postgresql://$DB_HOST:$PGPORT/mydatabase" \
  ${EDGE_TLS:+-e SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWKSETURI=http://host.docker.internal:8180/realms/shortener/protocol/openid-connect/certs} \
  -e SPRING_DATASOURCE_USERNAME=myuser -e "SPRING_DATASOURCE_PASSWORD=$PGPASS" \
  ${RATE_LIMIT_ARGS} -e SHORTENER_SHORTLINK_TARGETURLS_ALLOWANY=true -e SHORTENER_SECURITY_DPOP_NONCE_SECRET=benchmark-nonce-secret-for-one-run \
  ${DOCKER_ARGS:-} "$IMAGE" "$@" >/dev/null
until curl -sf -m 2 -o /dev/null localhost:8081/actuator/health/readiness; do
  [ -n "$(docker ps -q -f name=bench-app)" ] || { echo "the application container stopped:"; docker logs bench-app 2>&1 | tail -20; exit 1; }
  sleep 0.05
done
ready=$((($(date +%s%N) - t0) / 1000000))
if [ "$EDGE" = 1 ]; then
  docker run -d --name bench-edge --network bench-net --user 101:101 --read-only --tmpfs /tmp:rw,mode=1777,size=64m \
    --cpuset-cpus "$EDGE_CPUSET" --cpus 1 --memory 128m --cap-drop ALL --security-opt no-new-privileges:true \
    -v "$EDGE_CONF:/etc/nginx/nginx.conf:ro" -v "$EDGE_TLS/tls_cert:/run/secrets/tls_cert:ro" -v "$EDGE_TLS/tls_key:/run/secrets/tls_key:ro" \
    nginxinc/nginx-unprivileged:1.31.5-alpine >/dev/null
  EDGE_IP=$(docker inspect -f '{{(index .NetworkSettings.Networks "bench-net").IPAddress}}' bench-edge)
  until curl -sf -m 2 -o /dev/null "http://$EDGE_IP:8080/healthz"; do
    [ -n "$(docker ps -q -f name=bench-edge)" ] || { echo "the edge container stopped:"; docker logs bench-edge 2>&1 | tail -20; exit 1; }
    sleep 0.2
  done
  BASE_URL=https://$EDGE_IP:8443
  say "edge up at $BASE_URL (nginx, $(docker exec bench-edge nginx -v 2>&1 | cut -d/ -f2), $(docker exec bench-edge sh -c 'ps' 2>/dev/null | grep -c 'nginx: worker') workers)"
fi
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
  "scenario": "$SCENARIO",
  "topology": "$([ "$EDGE" = 1 ] && echo edge || echo direct)",
  "edge": {"enabled": $([ "$EDGE" = 1 ] && echo true || echo false), "cpuset": "$EDGE_CPUSET", "cpus": "1", "memory": "128m", "config": "$(js "$(basename "$(dirname "$EDGE_CONF")")/$(basename "$EDGE_CONF")")", "image": "nginxinc/nginx-unprivileged:1.31.5-alpine", "tls": "self-signed ECDSA P-256, HTTP/2"},
  "limits": {"app_cpuset": "$APP_CPUSET", "app_cpus": "$APP_CPUS", "app_memory": "$APP_MEMORY", "postgres_cpuset": "$PG_CPUSET", "postgres_cpus": "${PG_CPUS:-unlimited}", "postgres_memory": "${PG_MEMORY:-unlimited}", "rate_limits": "${RATE_LIMITS:-lifted}", "k6_cpuset": "$K6_CPUSET"},
  "load": {"executor": "constant-arrival-rate", "warmup": "$WARMUP_RATE req/s for $WARMUP_DURATION", "duration": "$DURATION", "creates_per_second": $CREATES_PER_SECOND, "repeat": $REPEAT, "cooldown_seconds": $COOLDOWN, "max_vus": 3000},
  "workload": {"dataset_file": "$(js "$(basename "${DATASET_FILE:-}")")", "dataset_rows": $DATASET_ROWS, "dataset_hot": ${DATASET_HOT:-0}, "dataset_hot_share": ${DATASET_HOT_SHARE:-0.8}, "seeded_links": $SEEDS}
}
J

k6() { # name rate duration share
  say "load $1: $2 requests/s for $3, a create share of $4"
  rm -f "$OUT/$1.k6.json"
  docker run --rm -t --name bench-k6 --network host --cpuset-cpus "$K6_CPUSET" -v "$HERE/k6:/scripts:ro" -v "$ROOT/deploy/keycloak/dev-keys:/keys:ro" -v "$OUT:/out" \
    -e RATE="$2" -e DURATION="$3" -e CREATE_SHARE="$4" -e SEEDS="$SEEDS" -e MAX_VUS=3000 -e BASE_URL="$BASE_URL" \
    ${DATASET_FILE:+-v "$DATASET_FILE:/dataset.txt:ro" -e DATASET_FILE=/dataset.txt} -e DATASET_HOT="${DATASET_HOT:-0}" -e DATASET_HOT_SHARE="${DATASET_HOT_SHARE:-0.8}" \
    grafana/k6 run $K6_TLS --summary-trend-stats "avg,min,med,max,p(90),p(95),p(99)" --summary-export "/out/$1.k6.json" /scripts/mixed.js </dev/null 2>&1 | tee "$OUT/$1.k6.txt" || true
  "$HERE/scrub-k6-summary.sh" "$OUT/$1.k6.json"
}

sampler() { while true; do echo "$(date +%s) $(docker stats --no-stream --format '{{.CPUPerc}} {{.MemUsage}}' bench-app 2>/dev/null)"; done; }
sampler >"$OUT/docker-stats.txt" &
SAMPLER=$!
k6_sampler() {
  while true; do
    reading=$(docker stats --no-stream --format '{{.CPUPerc}} {{.MemUsage}}' bench-k6 2>/dev/null || true)
    if [ -n "$reading" ]; then echo "$(date +%s) $reading"; else sleep 1; fi
  done
}
k6_sampler >"$OUT/docker-stats-k6.txt" &
SAMPLER_K6=$!
SAMPLER_EDGE=
if [ "$EDGE" = 1 ]; then
  edge_sampler() { while true; do echo "$(date +%s) $(docker stats --no-stream --format '{{.CPUPerc}} {{.MemUsage}}' bench-edge 2>/dev/null)"; done; }
  edge_sampler >"$OUT/docker-stats-edge.txt" &
  SAMPLER_EDGE=$!
fi
cleanup() {
  kill "$SAMPLER" $SAMPLER_EDGE $SAMPLER_K6 2>/dev/null || true
  [ -z "$PG_LIMITED" ] || docker update --cpus "$(nproc)" --memory 64g --memory-swap 64g "$PG_CONTAINER" >/dev/null 2>&1 || true
  [ -z "$(docker ps -aq -f name=bench-edge)" ] || {
    docker logs bench-edge >"$OUT/edge.full.log" 2>&1 || true
    tail -n 200 "$OUT/edge.full.log" >"$OUT/edge.log" || true
    grep -o '"status":[0-9]*' "$OUT/edge.full.log" | sort | uniq -c >"$OUT/edge-status.txt" || true
    grep -c 'limiting connections' "$OUT/edge.full.log" >>"$OUT/edge-status.txt" || true
    rm -f "$OUT/edge.full.log"
    docker rm -f bench-edge >/dev/null 2>&1 || true
  }
  [ "$EDGE" != 1 ] || { docker network rm bench-net >/dev/null 2>&1 || true; rm -rf "$EDGE_TLS"; }
  [ -z "$(docker ps -aq -f name=bench-app)" ] || { docker logs bench-app >"$OUT/app.log" 2>&1 || true; docker rm -f bench-app >/dev/null 2>&1 || true; }
}
trap cleanup EXIT

say "warming up for $WARMUP_DURATION"
k6 warmup "$WARMUP_RATE" "$WARMUP_DURATION" 0.005
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

kill $SAMPLER $SAMPLER_EDGE $SAMPLER_K6 2>/dev/null || true
docker logs bench-app >"$OUT/app.log" 2>&1
docker rm -f bench-app >/dev/null
say "$VARIANT finished; app log has $(grep -c OutOfMemoryError "$OUT/app.log" || true) OutOfMemoryError lines; results in $OUT"
"$ROOT/tools/run" Report json "$OUT/summary.json"
"$ROOT/tools/run" Report json "$OUT/metadata.json"
