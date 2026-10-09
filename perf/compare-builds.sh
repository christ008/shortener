#!/bin/sh
# Runs perf/bench.sh on each build of the application that is worth comparing, with the same load, and prints the comparison.
#   [RATES="6000 8000 10000 12000 14000"] [BUILDS="stack-image oracle-O3-v3 lite-aot"] perf/compare-builds.sh [--scenario app|edge|full] OUT_DIR
# Builds (BUILDS picks some, in the order given; the images other than the first are made by perf/builds/jvm.sh and perf/builds/native.sh):
#   jvm-temurin    Temurin 25 JRE, the jar mounted, the collector the JVM picks (Serial with 2 CPUs and 512 MiB)
#   lite           Liberica JDK Lite 25 (bellsoft/liberica-runtime-container:jdk-25-glibc), the same
#   lite-aot       the same with the AOT cache of the JDK
#   lite-serial, lite-g1, lite-zgc, lite-parallel   the Lite image with the collector named (ZGC is generational in JDK 25)
#   the same with -coh (lite-parallel-coh): and compact object headers, -XX:+UseCompactObjectHeaders (JEP 519, a product option in JDK 25)
#   ce-Os          native image, GraalVM Community Edition, -Os -march=compatibility (how the stack builds it, on glibc)
#   oracle-v3      native image, Oracle GraalVM, -march=x86-64-v3
#   oracle-O3-v3   native image, Oracle GraalVM, -O3 (GraalNN) -march=x86-64-v3
#   stack-image    shortener:VERSION, the image of the stack: a JVM since ADR 0036 (the native image of ADR 0017 was static musl on Alpaquita, Liberica NIK)
set -eu

SCENARIO_ARGUMENTS=
if [ "${1:-}" = --scenario ]; then SCENARIO_ARGUMENTS="--scenario ${2:?--scenario needs app, edge or full}"; shift 2; fi
OUT=${1:?usage: perf/compare-builds.sh [--scenario app|edge|full] OUT_DIR}
cd "$(dirname "$0")/.."
VERSION=$(sed -n 's/^version = "\(.*\)"/\1/p' build.gradle.kts)
BUILDS=${BUILDS:-"stack-image oracle-O3-v3 lite-aot"}
export RATES="${RATES:-6000 8000 10000 12000 14000}" DURATION="${DURATION:-60s}" COOLDOWN="${COOLDOWN:-15}"

./gradlew bootJar -q
for build in $BUILDS; do
  # shellcheck disable=SC2086
  case $build in
    jvm-temurin) DOCKER_ARGS="-v $PWD/build/libs/shortener-$VERSION.jar:/app.jar:ro" perf/bench.sh $SCENARIO_ARGUMENTS "$build" eclipse-temurin:25-jre "$OUT/$build" java -XX:MaxRAMPercentage=70 -jar /app.jar;;
    lite) perf/bench.sh $SCENARIO_ARGUMENTS "$build" shortener-bench:lite "$OUT/$build" java -XX:MaxRAMPercentage=70 -jar /app.jar;;
    lite-aot) perf/bench.sh $SCENARIO_ARGUMENTS "$build" shortener-bench:lite-aot "$OUT/$build" java -XX:MaxRAMPercentage=70 -XX:AOTMode=on -XX:AOTCache=/aot/app.aot -jar /app.jar;;
    lite-serial|lite-g1|lite-zgc|lite-parallel|lite-serial-coh|lite-g1-coh|lite-zgc-coh|lite-parallel-coh)
      collector=${build#lite-}
      headers=
      case $collector in *-coh) collector=${collector%-coh}; headers=-XX:+UseCompactObjectHeaders;; esac
      case $collector in serial) flag=-XX:+UseSerialGC;; g1) flag=-XX:+UseG1GC;; zgc) flag=-XX:+UseZGC;; parallel) flag=-XX:+UseParallelGC;; esac
      perf/bench.sh $SCENARIO_ARGUMENTS "$build" shortener-bench:lite "$OUT/$build" java -XX:MaxRAMPercentage=70 "$flag" $headers -Xlog:gc:stdout -jar /app.jar;;
    ce-Os|oracle-v3|oracle-O3-v3) perf/bench.sh $SCENARIO_ARGUMENTS "$build" "shortener-bench:$build" "$OUT/$build";;
    stack-image) perf/bench.sh $SCENARIO_ARGUMENTS "$build" "shortener:$VERSION" "$OUT/$build";;
    *) echo "unknown build $build" >&2; exit 2;;
  esac
done

printf '\n== comparison (%s)\n\n' "$OUT"
tools/run Report summary "$OUT"
printf '\n== CPU\n\n'
tools/run Report cpu "$OUT"
