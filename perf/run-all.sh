#!/bin/sh
# Runs the benchmark for the jar on a Temurin JRE and for the image of the current version, with the same limits, and prints the comparison.
#   perf/run-all.sh [RESULTS_DIR]
# Needs the compose services postgres, keycloak and prometheus (profile observability) and the image of the current version (./gradlew bootBuildImage; a JVM since ADR 0036).
set -eu
cd "$(dirname "$0")/.."
OUT=${1:-perf/results/$(date +%F-%H%M)}
VERSION=$(sed -n 's/^version = "\(.*\)"/\1/p' build.gradle.kts)
JAR=build/libs/shortener-$VERSION.jar

echo "building $JAR"
./gradlew bootJar -q

DOCKER_ARGS="-v $PWD/$JAR:/app.jar:ro" perf/bench.sh jvm eclipse-temurin:25-jre "$OUT/jvm" java -XX:MaxRAMPercentage=70 -jar /app.jar
perf/bench.sh native "shortener:$VERSION" "$OUT/native"

printf '\n\033[1m== comparison (%s)\033[0m\n\n' "$OUT"
tools/run Report summary "$OUT"
