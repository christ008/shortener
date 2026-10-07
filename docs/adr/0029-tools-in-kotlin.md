# 0029. The tools are Kotlin, in a build of their own

- Status: Accepted, 2026-10-06, and amended the same day: the tools do not use the DPoP client (see Decision). Supersedes the compute tier of [0026](0026-scripting-standard.md) and the tools of [0027](0027-tools-on-jdk-25.md). What they decided about `sh` for starting programs, and about the DPoP client being one Java file for JDK 17 ([0020](0020-dpop-client-in-java.md)), stands
- Evidence: `tools/`, `ToolsLauncherTest`, `SmokeTest`, `ReportTest`, `RealmsTest`, `DevSetupTest`

## Problem

[0026](0026-scripting-standard.md) put every script that computes in Java and [0027](0027-tools-on-jdk-25.md) moved the tools to
JDK 25 as compact source files. What that left, measured on one machine, one run each:

- **A second language in a Kotlin project.** The tools were 1,043 lines of Java (`Cli`, `DevSetup`, `RealmTemplate`, `Realms`,
  `Report`, `Smoke`) beside the 810-line client, against 2,206 lines of Kotlin in the application.
- **Tests that start a process for each case.** The tests of three tools ran the tool as a subprocess, inside the application's
  `./gradlew test`: 9 cases in 17.5 s for `Realms`, 11 in 31 s for `Report` and 5 in 45 s for `DevSetup`. A tool exits and
  reads the working directory and the environment of the process, so it could not be called from a test any other way. The
  deployment files that those tests read were inputs of every test task of the application.
- **What was not tested.** `Smoke`, which decides whether a release is published, had no test and was only tried against a
  live instance. The questions `dev-setup` asks on a terminal were tried by hand.
- **Tools reaching into the client.** `Report` read JSON with the reader inside `DpopClient.java`, which is the one file that
  people outside the project run, and `Smoke` and `DevSetup` were going to compile it and run it.
- **The reason for rejecting Kotlin no longer holds.** [0026](0026-scripting-standard.md) rejected it as "a runtime or a build
  the project does not otherwise need for tooling". The project builds with Gradle and Kotlin.

## Decision

- **The tools are Kotlin, in `tools/`, a Gradle build of its own.** The application's build is not configured to run a tool,
  because its toolchain asks for a JDK 25: `./gradlew help` on the application's build fails on a machine without one, and
  `./gradlew -p tools installDist` built and ran the tools with only a JDK 21. `settings.gradle.kts` includes it so that
  `./gradlew test` tests the tools too, which is all CI and a release run; it is configured only when one of its tasks is asked for.
- **They need a JDK 25**, the application's, to build them (17, then 21, until 2026-10-07: `Dataset` uses stream gatherers, final in 24,
  and 25 is the LTS after it). `tools/run` says so when the java it finds is older, or is a JRE (no `lib/ct.sym`, which `-Xjdk-release`
  reads). A JRE 25 runs them once built. The bytecode is 25 and `-Xjdk-release=25` keeps a later API out. CI builds and runs them on
  25 in the `stack` job. The DPoP client stays at 17 ([0020](0020-dpop-client-in-java.md)).
  - Cost: a machine with only a JDK 21 can no longer run the tools, which `./gradlew -p tools installDist` did before. The README
    already asks for a JDK 25 for the application, and `JAVA_HOME` is what `tools/run` reads first.
- **`tools/run TOOL` is still how a tool starts**, with the same names (`Realms`, `DevSetup`, `Smoke`, `Report`), so `perf/*.sh`,
  the workflows and the shims did not change. It asks Gradle to build only when a source is newer than the last build.
- **A tool runs against a `Context`** (the root of the repository, the environment, the streams and whoever may be asked), and
  returns its status. A test calls it in process, with a directory and an environment of its own.
- **The tools do not use the DPoP client.** `DpopClient.java` is the reference for people outside the project, so nothing of the
  tools may depend on it: a change to the reference would break a tool, and a change a tool needs would be held back by the
  reference. It is not compiled with the tools, `tools/run` does not watch it, and it has no entry point for them. What the tools
  need of it is written again, with only what the JDK has: `ClientKeys` (P-256 JWKs, 95 lines) and `DpopCalls` (the assertion
  that authenticates a client, the proof of each request, the token, the call, 144 lines). `Smoke` calls `DpopCalls`, and
  `DevSetup` calls `ClientKeys`.
- **JSON is Jackson**, which the application already uses.

## Consequences

- Good: the tests run in process. `RealmsTest` took 0.3 to 0.7 s (9 cases, 17.5 s before), `ReportTest` 1.0 to 1.5 s (11 cases,
  31 s before) and `DevSetupTest` under 0.1 s (8 cases, 5 and 45 s before: the keys are made in process).
- Good: what had no test has one. `Smoke` runs against a stand-in of the API, and fails on one wrong answer, on no answer at all
  and on too many arguments. `dev-setup`'s questions run against a prompt that answers from a list. When `tools/run` builds, and
  when it does not, is tested against a `gradlew` that only records its calls.
- Good: the output is the same. Each tool was compared with its Java version on the same inputs: `Realms` byte for byte, `DevSetup`
  in what it prints and writes apart from the keys, which are random, and `Report` on every result the repository holds. A heap dump cut short, and a number that is not a number, are now said
  in words and not as a stack trace.
- Good: no tool depends on the client, and `dev-setup` makes its four keys in process, which took 0.8 s each as a process of the client.
- Cost: **the protocol is written twice**, in `DpopClient.java` and in `ClientKeys` and `DpopCalls`, and a fix to one is not a fix to
  the other. That was chosen over sharing. Each has tests that read what it wrote with a library that is not the one that wrote
  it (Nimbus), and the files they make have the members the other reads: the same JWK for a client key.
- Cost: **the first run builds**. With the dependencies already downloaded and no Gradle daemon running it took 33 s here;
  downloading them comes on top of that, and was not timed. A run after it takes about 1.1 s, as
  `java tools/Realms.java` did (1.2 s), because `tools/run` does not ask Gradle again until a source changes. Asking it anyway
  cost 1.5 s with a warm daemon and 10.7 s with a cold one. JVM flags for a short process gained 5 to 12%, which is not worth a setting.
- Cost: **`dev-setup`, the first step of the README, needs the Gradle wrapper**, which is more to download than a JDK. A person
  who only wants to call the API still needs only the client.
- Cost: versions are written twice, Kotlin and the Spring Boot BOM in `tools/build.gradle.kts` as in the application's build, and
  Dependabot watches neither here. A test dependency (Nimbus) is pinned by hand because the Boot BOM does not manage it.
- Cost: `./gradlew devSetup`, which runs `tools/run`, starts a second Gradle, and a tool can no longer be run as `java File.java`.
- Cost: this replaces [0026](0026-scripting-standard.md) and [0027](0027-tools-on-jdk-25.md) on the day they were accepted.

## Rejected

- Keeping them in Java: the cost is above, and the only thing it had in its favour was starting without a build.
- A module of the application's build: running a tool would configure the application and need its JDK 25, and the tests of the
  tools would still belong to it.
- Compiling `DpopClient.java` with the tools, and calling it as a library or as a process: one client and no copy, which is what
  this was first, and the coupling above.
- Moving the DPoP client to Kotlin: it is what people outside the project run with only a JDK 17 and no build
  ([0020](0020-dpop-client-in-java.md)).
- Not in the history: Kotlin scripts (`.main.kts`) run without a build. They were not tried.
