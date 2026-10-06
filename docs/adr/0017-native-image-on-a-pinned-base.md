# 0017. A GraalVM native image on a pinned Alpaquita base

- Status: Accepted, 2026-10-03, pinned 2026-10-05
- Evidence: `96fde0c`, `2128bd8`, `623d3d8`, `perf/smoke.sh`, `INTERNALS.md#native-image`

## Problem

The service starts often (rollouts, restarts) and runs in 512 MB. A JVM takes about 5 seconds to be ready and 250 MiB at
rest. The image also carries whatever its base carries, and a rebuild of the same commit should give the same image.

## Decision

- `./gradlew bootBuildImage` builds a native image through Paketo buildpacks with Liberica NIK, on BellSoft's Alpaquita
  (musl) builder and run image.
- Pinned: the buildpacks by version, in a list that replaces the builder's order, and the builder and run image by
  digest, because BellSoft publishes only rolling tags for them.
- `-Os`: the binary is 125 MB instead of 157 MB, for about 10% more CPU at the same load.
- `-march=compatibility`, so it runs on any x86-64 host.
- Runs as uid 1000, ready in 0.4 s, healthy under `--read-only`, `--cap-drop ALL` and `no-new-privileges`.
- A Tiny Health Checker binary is in the image, so a health check needs no shell or `curl`.

## Consequences

- Good: 0.4 s to ready, about 100 MB at rest, and a small base.
- Cost, found the hard way: the image decides at build time which beans exist, so things fail silently that the JVM
  accepts. Caffeine's generated classes, the DPoP filter, SpEL's reflective reads and the management port each needed a
  hint or a build-time setting. The JVM tests cannot see these, so `perf/smoke.sh` runs 21 checks against the image.
- Cost: builds take 3 minutes and about 7 GB of memory. Only amd64 is built.
- Cost: the run image still has a shell (busybox).

## Rejected

- JVM image: simpler to build and to debug, and slower to start. Its numbers are in `INTERNALS.md#performance` if the
  trade turns out wrong.
- UPX: shrinks the binary further and costs startup time and memory sharing.
