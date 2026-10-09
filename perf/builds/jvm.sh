#!/bin/sh
# Builds an image that runs the application's jar on a JDK, for perf/bench.sh, with or without the AOT cache of the JDK (JEP 483, 514 and 515):
# the classes loaded and linked, and what the JIT learned, kept from a run for the next ones.
#   perf/builds/jvm.sh TAG BASE_IMAGE [aot]        for example:
#   perf/builds/jvm.sh lite-aot bellsoft/liberica-runtime-container:jdk-25-glibc aot
# - The jar is built by Gradle and copied into the image, because the cache is only valid for the jar it was made from.
# - With `aot`, a container of the image runs with the CPU and memory limits of the benchmark and -XX:AOTMode=record, k6 sends it the warm-up
#   load of the benchmark, and stopping it writes the configuration; a run of its own with -XX:AOTMode=create makes the cache from it, which a
#   second image carries. Run that with -XX:AOTCache=/aot/app.aot and the same collector (the benchmark leaves it to the JVM, which picks Serial
#   with 2 CPUs and 512 MiB).
# Needs the compose Postgres and Keycloak, as perf/bench.sh does.
set -eu

TAG=${1:?usage: perf/builds/jvm.sh TAG BASE_IMAGE [aot]}
BASE=${2:?usage: perf/builds/jvm.sh TAG BASE_IMAGE [aot]}
MODE=${3:-}
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
cd "$ROOT"
VERSION=$(sed -n 's/^version = "\(.*\)"/\1/p' build.gradle.kts)

./gradlew bootJar -q
CONTEXT=$(mktemp -d)
trap 'rm -rf "$CONTEXT"; docker rm -f jvm-train >/dev/null 2>&1 || true' EXIT
cp "build/libs/shortener-$VERSION.jar" "$CONTEXT/app.jar"
printf 'FROM %s\nCOPY app.jar /app.jar\n' "$BASE" >"$CONTEXT/Dockerfile"
docker build -q -t "shortener-bench:$TAG" "$CONTEXT" >/dev/null

if [ "$MODE" = aot ]; then
  PGPASS=$(sed -n "s/^DEV_POSTGRES_PASSWORD='\{0,1\}\([^']*\)'\{0,1\}$/\1/p" .env)
  PGPORT=$(docker compose port postgres 5432 | cut -d: -f2)
  mkdir -p "$CONTEXT/aot"
  chmod 777 "$CONTEXT/aot"
  docker rm -f jvm-train >/dev/null 2>&1 || true
  # Record: the JVM writes what it loaded and linked, and what the JIT learned, to the configuration when it exits.
  docker run -d --name jvm-train --network host --cpus 2 --memory 512m -v "$CONTEXT/aot:/aot" \
    -e SPRING_DATASOURCE_URL="jdbc:postgresql://localhost:$PGPORT/mydatabase" -e SPRING_DATASOURCE_USERNAME=myuser -e "SPRING_DATASOURCE_PASSWORD=$PGPASS" \
    -e SHORTENER_SECURITY_RATELIMIT_PERIP_CAPACITY=1000000000 -e SHORTENER_SECURITY_RATELIMIT_PERCLIENT_CAPACITY=1000000000 \
    "shortener-bench:$TAG" java -XX:MaxRAMPercentage=70 -XX:AOTMode=record -XX:AOTConfiguration=/aot/app.aotconf -jar /app.jar >/dev/null
  until curl -sf -m 2 -o /dev/null localhost:8081/actuator/health/readiness; do
    [ -n "$(docker ps -q -f name=jvm-train)" ] || { docker logs jvm-train 2>&1 | tail -20; exit 1; }
    sleep 0.5
  done
  docker run --rm --network host -v "$ROOT/perf/k6:/scripts:ro" -v "$ROOT/deploy/keycloak/dev-keys:/keys:ro" \
    -e RATE=2000 -e DURATION=40s -e CREATE_SHARE=0.005 -e SEEDS=300 -e MAX_VUS=1000 grafana/k6 run --quiet /scripts/mixed.js </dev/null >/dev/null 2>&1 || true
  docker stop -t 120 jvm-train >/dev/null
  [ -s "$CONTEXT/aot/app.aotconf" ] || { echo "the JVM left no configuration:"; docker logs jvm-train 2>&1 | tail -20; exit 1; }
  # Create: a run of its own that turns the configuration into the cache and does not start the application.
  docker run --rm --cpus 2 --memory 512m -v "$CONTEXT/aot:/aot" "shortener-bench:$TAG" \
    java -XX:MaxRAMPercentage=70 -XX:AOTMode=create -XX:AOTConfiguration=/aot/app.aotconf -XX:AOTCache=/aot/app.aot -jar /app.jar >"$CONTEXT/create.log" 2>&1 || true
  [ -s "$CONTEXT/aot/app.aot" ] || { echo "no cache was made:"; grep -v '\[aot\]' "$CONTEXT/create.log" | tail -20; exit 1; }
  cp "$CONTEXT/aot/app.aot" "$CONTEXT/app.aot"
  printf 'FROM %s\nCOPY app.jar /app.jar\nCOPY app.aot /aot/app.aot\n' "$BASE" >"$CONTEXT/Dockerfile"
  docker build -q -t "shortener-bench:$TAG" "$CONTEXT" >/dev/null
  echo "== AOT cache of $(du -h "$CONTEXT/app.aot" | cut -f1) in shortener-bench:$TAG"
fi
echo "== shortener-bench:$TAG on $BASE"
