# 0036. A JVM image from buildpacks, built on arm64

- Status: Accepted, 2026-10-09
- Supersedes [0017](0017-native-image-on-a-pinned-base.md)
- Evidence: `build.gradle.kts` (`bootBuildImage`), `.github/workflows/release.yml`, `.github/workflows/image.yml`, `perf/smoke.sh`, `INTERNALS.md#image`

## Problem

The first public instance is meant to run on Oracle Cloud's Always Free tier, which is Ampere A1: arm64, up to 4 cores and 24 GB for the
whole account. The native image of [0017](0017-native-image-on-a-pinned-base.md) was built for amd64 only (`-march=compatibility`), needs a
build host with 7 GB free, and on the same load cost about twice the CPU a request of a warm JVM, which sustained at least twice its rate
([Performance](../INTERNALS.md#performance)). On a machine whose limit is cores, the cost of a request is what is paid for.

## Decision

- The image is still built by `./gradlew bootBuildImage`, now with the builder's own buildpacks and without the native-image step. `syft` is pinned to 2.41.0,
  because the builder's 2.42.1 downloads an amd64 binary on arm64 and stops the build with `exec format error` ([paketo-buildpacks/syft#479](https://github.com/paketo-buildpacks/syft/issues/479), open).
  It runs on a Liberica JRE Lite 25, on BellSoft's Alpaquita glibc builder and run image, the libc that was measured.
- The builder and run image are pinned by digest, because BellSoft publishes rolling tags only. The digest pins the buildpacks the builder carries.
  `health-checker` and that `syft` are added, by version, and the list names the builder's buildpacks instead of the composite. The list of buildpacks that [0017](0017-native-image-on-a-pinned-base.md) pinned one by one is gone.
- The GraalVM plugin is applied only with `-Pnative`. Applied, it makes `bootJar` run Spring AOT and mark the jar `Spring-Boot-Native-Processed`,
  which the `spring-boot` buildpack reads as a request for a native image (the detector fails without a native builder). The jar of the image is a
  plain Spring Boot jar, and `perf/builds/native.sh` passes `-Pnative`.
- Spring AOT, the JVM's CDS and its AOT cache stay off ([INTERNALS.md#image](../INTERNALS.md#image) says what each does and what was measured).
- The JVM runs with G1, `-XX:+UseCompactObjectHeaders` and a 64 MB code cache. The memory calculator of the buildpack sets the heap from the
  container's limit: 50 thread stacks (the threads are virtual), the metaspace for the loaded classes, 10 MB of direct memory and that code cache
  come off the 512 MiB, and a heap of 274 MiB is left. Native memory tracking and the Spring Cloud Bindings are off.
- The image runs under the `production` profile unless told otherwise, as the native image, built under it, always did. The health check stays
  Tiny Health Checker, so the stack does not change.
- The Release workflow runs on an arm64 runner (free for public repositories), builds there, checks that the image is arm64, and smoke-tests that
  exact image before it publishes it. The image takes the platform of the machine that builds it.

## Consequences

- Good: arm64 and amd64 from the same build, a build that needs no 7 GB host and no three minutes, the supply chain of [0017](0017-native-image-on-a-pinned-base.md)
  (pinned builder, SBOM layer, OCI labels) unchanged, and none of the hints that the native image needed. The hints stay in the code and do nothing here.
- Good: a profile or a property that is set when the container starts has an effect again.
- Cost: the JIT warms for about 15 seconds, in which 1 to 3% of the requests at 5,000 req/s did not complete, and a task is ready after about 4 seconds instead
  of 0.7. A task that rolls in should not take the full load at once. Nothing does that yet.
- Cost: the heap is set by the buildpack's calculator, whose defaults do not fit 512 MiB (it stops with "fixed memory regions require 635277K"): the code cache
  and the thread count had to be set, and the heap is 274 MiB where the measured runs had 358. The load was sustained with a heap of 179 MiB (256 MiB container).
- Cost: setting `JAVA_TOOL_OPTIONS` at run time adds to the flags and keeps them, but the memory calculator still decides `-Xmx`; to size the heap by hand,
  set `-Xmx` there.
- Cost: the image is about 400 MB uncompressed (the native image was about 205 MB) and carries a JRE and a shell.
- Not measured: anything on Ampere. The figures are from an x86-64 desktop with 2 CPUs a task; the collector, the heap and the 512 MiB limit are to be checked
  on the real cores, which are cores and not hyperthreads. The buildpacks' arm64 variants exist and were not run.
- Only arm64 is published. An amd64 host builds its own with `./gradlew bootBuildImage`.

## Rejected

- Not in the history: a `Dockerfile` on the Liberica Lite image, with the measured flags (`-XX:MaxRAMPercentage=70`). It was built and passed the smoke test
  and the hardening options. It gives up what the buildpacks give and the supply chain relies on, and was dropped on the maintainers' decision to build with
  buildpacks.
- A native image for arm64: Liberica NIK has one, and it was not built. The measurements said a JVM costs less for each request, and the native
  image's remaining advantage, its start, is what a warm-up in front of a task addresses.
- Publishing amd64 and arm64 together: two runners, a manifest and a smoke test of each, for a second platform nobody runs yet.
- Spring AOT on the JVM: about 0.6 s of 4.9 s of start, in exchange for the choices that [0017](0017-native-image-on-a-pinned-base.md) found the hard way (beans, profile and
  conditions fixed at build time) and a jar that can be started two ways. Worth taking again if the start matters more.
- The JDK's AOT cache (`BP_JVM_AOTCACHE_ENABLED`): it cut the start to 3.9 s and the early latency, for up to 25% more CPU a request once warm, and its training run starts
  the application in the build, where there is no Postgres (not tried). Worth taking again with the warm-up.
