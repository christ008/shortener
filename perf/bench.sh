#!/usr/bin/env bash
# Benchmarks one image of the application against the compose Postgres and Keycloak and records what it did.
#   [RUNS="name rate duration create_share|..."] [DOCKER_ARGS="..."] perf/bench.sh VARIANT IMAGE OUT_DIR [COMMAND...]
# The app gets cores 0-1 and the production memory limit, Postgres cores 2-5 and k6 cores 6-9, so that the three
# do not compete. Needs Postgres, Keycloak and Prometheus (profile observability) running: the compose services, or
# containers of your own with PGPORT and PG_CONTAINER set.
set -euo pipefail

VARIANT=$1; IMAGE=$2; COMMAND=("${@:4}")
mkdir -p "$3"; OUT=$(cd "$3" && pwd); chmod 777 "$OUT"
HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/.." && pwd)
PGPASS=$(sed -n "s/^DEV_POSTGRES_PASSWORD='\{0,1\}\([^']*\)'\{0,1\}$/\1/p" "$ROOT/.env" 2>/dev/null)
[ -n "$PGPASS" ] || { echo "no Postgres password in $ROOT/.env: run deploy/keycloak/dev-setup first" >&2; exit 1; }
PGPORT=${PGPORT:-$(docker compose -f "$ROOT/compose.yaml" port postgres 5432 | cut -d: -f2)}
PG_CONTAINER=${PG_CONTAINER:-$(docker compose -f "$ROOT/compose.yaml" ps -q postgres)}
PROM=http://localhost:9090
say() { printf '\n\033[1m[%s] %s\033[0m\n' "$(date +%T)" "$*"; }

say "== $VARIANT: $IMAGE ${COMMAND[*]:-}"
docker update --cpuset-cpus 2-5 "$PG_CONTAINER" >/dev/null
docker rm -f bench-app >/dev/null 2>&1 || true

t0=$(date +%s.%N)
docker run -d --name bench-app --network host --cpuset-cpus 0-1 --cpus 2 --memory 512m \
  -e SPRING_DATASOURCE_URL="jdbc:postgresql://localhost:$PGPORT/mydatabase" \
  -e SPRING_DATASOURCE_USERNAME=myuser -e "SPRING_DATASOURCE_PASSWORD=$PGPASS" \
  -e SHORTENER_SECURITY_RATELIMIT_PERIP_CAPACITY=1000000000 -e SHORTENER_SECURITY_RATELIMIT_PERCLIENT_CAPACITY=1000000000 \
  ${DOCKER_ARGS:-} "$IMAGE" "${COMMAND[@]}" >/dev/null
until curl -sf -m 2 -o /dev/null localhost:8081/actuator/health/readiness; do
  [ -n "$(docker ps -q -f name=bench-app)" ] || { echo "the application container stopped:"; docker logs bench-app 2>&1 | tail -20; exit 1; }
  sleep 0.05
done
ready=$(python3 -c "print(round(($(date +%s.%N) - $t0) * 1000))")
sleep 5
idle_mem=$(docker stats --no-stream --format '{{.MemUsage}}' bench-app | cut -d/ -f1 | xargs)
started=$(docker logs bench-app 2>&1 | grep -oE "Started .* in [0-9.]+ seconds" | head -1)
say "up: ready after ${ready} ms from docker run, ${started}, memory at rest ${idle_mem}"
docker exec "$PG_CONTAINER" psql -q -U myuser -d mydatabase -c 'TRUNCATE short_link' >/dev/null 2>&1 || true

k6() { # name rate duration share seeds
  say "load $1: $2 requests/s for $3, $(python3 -c "print(round($4 * 100, 2))")% of them creates"
  docker run --rm -t --network host --cpuset-cpus 6-9 -v "$HERE/k6:/scripts:ro" -v "$ROOT/deploy/keycloak/dev-keys:/keys:ro" -v "$OUT:/out" \
    -e RATE="$2" -e DURATION="$3" -e CREATE_SHARE="$4" -e SEEDS=300 -e MAX_VUS=3000 \
    grafana/k6 run --summary-trend-stats "avg,min,med,max,p(90),p(95),p(99)" --summary-export "/out/$1.k6.json" /scripts/mixed.js 2>&1 | tee "$OUT/$1.k6.txt" || true
}

sampler() { while true; do echo "$(date +%s) $(docker stats --no-stream --format '{{.CPUPerc}} {{.MemUsage}}' bench-app 2>/dev/null)"; done; }
sampler >"$OUT/docker-stats.txt" &
SAMPLER=$!

say "warming up for 30s"
k6 warmup 2000 30s 0.005
echo "{\"variant\":\"$VARIANT\",\"image\":\"$IMAGE\",\"ready_ms\":$ready,\"idle_mem\":\"$idle_mem\",\"started\":\"$started\",\"runs\":[" >"$OUT/summary.json"
first=1
RUNS=${RUNS:-"r1500 1500 60s 0.01|r5000 5000 60s 0.003|r10000 10000 30s 0.0015"}
IFS='|' read -ra RUN_LIST <<<"$RUNS"
for run in "${RUN_LIST[@]}"; do
  set -- $run
  start=$(date +%s)
  k6 "$1" "$2" "$3" "$4"
  end=$(date +%s); sleep 7
  dur=$((end - start + 7))
  q() { curl -s -m10 --data-urlencode "query=$1" --data-urlencode "time=$((end + 7))" "$PROM/api/v1/query" | python3 -c 'import sys,json;d=json.load(sys.stdin)["data"]["result"];print(d[0]["value"][1] if d else "null")'; }
  h() { q "histogram_quantile($1, sum by (le) (increase(http_server_requests_seconds_bucket{$2}[${dur}s])))"; }
  [ $first = 1 ] || echo "," >>"$OUT/summary.json"; first=0
  say "server side for $1 (from Prometheus): redirect p50/p95/p99 = $(h 0.5 'uri="/{shortCode}"' | xargs -I{} python3 -c "print(round(float('{}')*1000,1))" 2>/dev/null) / $(h 0.95 'uri="/{shortCode}"' | xargs -I{} python3 -c "print(round(float('{}')*1000,1))" 2>/dev/null) / $(h 0.99 'uri="/{shortCode}"' | xargs -I{} python3 -c "print(round(float('{}')*1000,1))" 2>/dev/null) ms; container now: $(docker stats --no-stream --format '{{.CPUPerc}} cpu, {{.MemUsage}}' bench-app)"
  cat >>"$OUT/summary.json" <<J
{"name":"$1","rate":$2,"duration":"$3","create_share":$4,"start":$start,"end":$end,
 "server":{"redirect_p50":$(h 0.5 'uri="/{shortCode}"'),"redirect_p95":$(h 0.95 'uri="/{shortCode}"'),"redirect_p99":$(h 0.99 'uri="/{shortCode}"'),
 "create_p50":$(h 0.5 'uri="/api/short-links",method="POST"'),"create_p99":$(h 0.99 'uri="/api/short-links",method="POST"'),
 "hikari_acquire_max":$(q "max_over_time(hikaricp_connections_acquire_seconds_max[${dur}s])"),"hikari_timeouts":$(q "increase(hikaricp_connections_timeout_total[${dur}s])"),
 "gc_pause_seconds":$(q "sum(increase(jvm_gc_pause_seconds_sum[${dur}s]))"),"process_cpu_avg":$(q "avg_over_time(process_cpu_usage[${dur}s])")}}
J
done
echo "]}" >>"$OUT/summary.json"

kill $SAMPLER 2>/dev/null || true
docker logs bench-app >"$OUT/app.log" 2>&1
docker rm -f bench-app >/dev/null
say "$VARIANT finished; app log has $(grep -c OutOfMemoryError "$OUT/app.log" || true) OutOfMemoryError lines; results in $OUT"
python3 -m json.tool "$OUT/summary.json" >/dev/null
