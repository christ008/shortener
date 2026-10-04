#!/usr/bin/env bash
# Runs the benchmark for the JVM and for the native image, with the same limits, and prints the comparison.
#   perf/run-all.sh [RESULTS_DIR]
# Needs the compose services postgres, keycloak and prometheus (profile observability) and the image shortener:0.11.0.
set -euo pipefail
cd "$(dirname "$0")/.."
OUT=${1:-perf/results/$(date +%F-%H%M)}
JAR=build/libs/shortener-$(sed -n 's/^version = "\(.*\)"/\1/p' build.gradle.kts).jar

echo "building $JAR"
./gradlew bootJar -q

DOCKER_ARGS="-v $PWD/$JAR:/app.jar:ro" perf/bench.sh jvm eclipse-temurin:25-jre "$OUT/jvm" java -XX:MaxRAMPercentage=70 -jar /app.jar
perf/bench.sh native shortener:0.11.0 "$OUT/native"
perf/bench.sh native-young30 shortener:0.11.0 "$OUT/native-young30" -XX:MaximumYoungGenerationSizePercent=30

printf '\n\033[1m== comparison (%s)\033[0m\n\n' "$OUT"
python3 perf/report.py "$OUT"
