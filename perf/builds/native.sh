#!/bin/sh
# Builds the application's native image with a GraalVM that SDKMAN installed, and puts the executable in an image of its own for perf/bench.sh.
#   perf/builds/native.sh TAG GRAALVM "NATIVE_IMAGE_OPTIONS"        for example:
#   perf/builds/native.sh oracle-v3 25.4.4+1-graal "-march=x86-64-v3"
# - TAG names the image, shortener-bench:TAG. GRAALVM is a directory of ~/.sdkman/candidates/java. The options are the ones of `native-image`
#   (`-O3` is the optimization level whose profile comes from GraalNN, Oracle GraalVM only; `-march=list` says what a machine can be given).
# - The executable is dynamic on glibc and runs on debian:stable-slim, where the image of the stack is static musl on Alpaquita: a build made here
#   against another made here differs only in what is passed, and against the stack's image also in the libc.
# - Gradle sees no other JDK for this build, so the plugin cannot pick another GraalVM. Needs about 7 GB of free memory and 3 to 5 minutes.
set -eu

TAG=${1:?usage: perf/builds/native.sh TAG GRAALVM "NATIVE_IMAGE_OPTIONS"}
GRAALVM=${2:?usage: perf/builds/native.sh TAG GRAALVM "NATIVE_IMAGE_OPTIONS"}
OPTIONS=${3:-}
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
HOME_GRAALVM=$HOME/.sdkman/candidates/java/$GRAALVM
[ -x "$HOME_GRAALVM/bin/native-image" ] || { echo "no native-image in $HOME_GRAALVM: sdk install java $GRAALVM" >&2; exit 1; }

cd "$ROOT"
echo "== $("$HOME_GRAALVM/bin/native-image" --version | head -2 | tr '\n' ' ')with: $OPTIONS"
JAVA_HOME=$HOME_GRAALVM GRAALVM_HOME=$HOME_GRAALVM NATIVE_IMAGE_OPTIONS="-J-Xmx7g $OPTIONS" \
  ./gradlew nativeCompile -Pnative -x test -q -Dorg.gradle.java.installations.auto-detect=false -Dorg.gradle.java.installations.paths="$HOME_GRAALVM"

OUTPUT=build/native/nativeCompile
[ -x "$OUTPUT/shortener" ] || { echo "no $OUTPUT/shortener: the build left $(find "$OUTPUT" -maxdepth 1 -type f 2>/dev/null | tr '\n' ' ')" >&2; exit 1; }
CONTEXT=$(mktemp -d)
trap 'rm -rf "$CONTEXT"' EXIT
# The executable and the shared libraries (libawt, libjava, ...) that the build puts beside it, which it looks for there.
mkdir "$CONTEXT/app"
cp "$OUTPUT"/* "$CONTEXT/app/"
cat >"$CONTEXT/Dockerfile" <<D
FROM debian:stable-slim
COPY app/ /app/
USER 1000:1000
ENTRYPOINT ["/app/shortener"]
LABEL graalvm="$GRAALVM" native-image-options="$OPTIONS"
D
docker build -q -t "shortener-bench:$TAG" "$CONTEXT" >/dev/null
echo "== shortener-bench:$TAG: $(du -h "$OUTPUT/shortener" | cut -f1) executable"
