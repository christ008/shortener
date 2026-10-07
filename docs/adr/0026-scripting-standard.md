# 0026. One standard for scripts: sh to start programs, Java to compute

- Status: Superseded by [0029](0029-tools-in-kotlin.md), which replaces its Java compute tier; the `sh` tier for starting programs stands. Accepted 2026-10-06. The Java tier's baseline was refined by [0027](0027-tools-on-jdk-25.md). Supersedes the shell client of [0019](0019-no-secrets-in-git.md), as [0020](0020-dpop-client-in-java.md)
  began to
- Evidence: the inventory in [OPERATING.md](../OPERATING.md#scripts), `0f4c18d`, `a412d3a`

## Problem

The repository grew 19 scripts in five languages: `sh` (8), `bash` (6), Python (3), one Java file of 781 lines and a k6
workload. Nothing said which to use. The same two helpers, `replace` and `json_in_json`, exist twice in `awk` and `sed`. Python
is used for three analysis scripts and nowhere else. Only the `deploy` scripts were linted. The one client that had to be
right, the DPoP client, was written in shell and then rewritten in Java ([0020](0020-dpop-client-in-java.md)) because shell
could not parse JSON or sign ES256 without hand-made DER. Each new feature added another dialect.

## Decision

Two tiers, chosen by what the script does and where it runs.

| Tier | Is for | Written in | Held by |
|---|---|---|---|
| **Start programs** | running other programs in order: `docker`, `psql`, `openssl`, `kc.sh`. Anything that runs on a production node, in a container or in Postgres's init, where there may be no JDK | POSIX `sh`: `#!/bin/sh`, `set -eu`, no arrays, no `pipefail`, no `[[`, no here-strings | `shellcheck --shell=sh` in CI |
| **Compute** | parsing, templating, keys, checks, reports, anything with a loop over data or a decision | Java as one source file run by `java File.java`, for the JDK 17 baseline | JUnit, which compiles it with `--release 17` and runs it, as `DpopClientTest` does |

- **The test for which tier**: if it needs `awk`, `sed` or `grep` to read structured text, or an arithmetic loop, it is compute.
  If it is a sequence of commands with `if` on exit codes, it is start programs.
- **No other languages.** Python goes. Bash goes, except where the file's purpose is a bash feature, which today is none.
  k6 stays: its workload is the tool's own language, not a script of ours.
- **Size.** A start-programs script that passes about 60 lines is a sign that compute has crept in.
- **No logic twice.** A helper used by two scripts is a Java command both call, not a copy.
- A script says in its first lines what it is for, how to run it and what it needs.

## What changes

In this order, each a change of its own, and none changes behavior:

1. This record, the inventory, and the linter by dialect in CI. `entrypoint.sh`, the one `bash` script in the production path, is
   POSIX `sh`.
2. `tools/Realms.java` (first `deploy/keycloak/`, moved by [0027](0027-tools-on-jdk-25.md)): the realm for development and for production, from their templates. It replaced the `awk`
   in `dev-setup` and the script `make-production-realm`, which is gone. Done 2026-10-06.
3. `dev-setup`: its prompting, random secrets and `.env` merge moved to `tools/DevSetup.java`, and the realm logic both it and
   `Realms` use to `tools/RealmTemplate.java`. What remains of the script is under 30 lines that find a JDK 25. Done 2026-10-06.
4. `tools/Smoke.java` for `smoke.sh`, which called `DpopClient.java` once per check (done 2026-10-06: the 21 checks in one process, 2.4 s
   against 16 s, run against a live instance), and `tools/Report.java` for the three
   Python scripts.
   Gradle tasks for every script a person runs: done 2026-10-06.
5. (Done 2026-10-06, and run end to end with short runs, 100 and 300 requests a second.) The `bash` scripts of `perf/` (`bench.sh`, `run-all.sh`, `profile.sh`, `tune-connections.sh`) to POSIX `sh`, with what parses
   output in `Report` (Java then, Kotlin since [0029](0029-tools-in-kotlin.md)).

## Consequences

- Good: a contributor knows which language a script is in before writing it, and each logic script has a unit test.
- Good: nothing the production node runs needs more than `sh` and Docker.
- Good: the two copies of the same helper become one.
- Cost: a JDK 17 on the machine of whoever runs a compute script. It is already needed for `dev-setup` and the DPoP client.
- Cost: a task is one more name for a script, and `dev-setup` cannot ask its questions through Gradle, so `devSetup` takes the
  defaults.
- Cost: one source file cannot import another before JDK 22, so a shared helper is a command both call, or one file with
  subcommands. [DpopClient](../../deploy/keycloak/DpopClient.java) is 781 lines because of it.
- Cost: `java File.java` starts in about a second, which is slow for a loop in a script and irrelevant for what is run here.
- The front door is Gradle: `./gradlew tasks --group tooling` lists every script that a person runs, and each task only starts its
  script (`gradle/tooling.gradle.kts`). A script still runs by hand. `ToolingTasksTest` fails when a task names a file that is
  gone or a script under `deploy/` has neither a task nor a reason not to.

## Rejected

- All Java: `deploy.sh` would need a JDK on the production node only to deploy, and container entrypoints must be `sh`.
- All shell, with `shfmt` and a `justfile`: it leaves JSON, templates and keys in `awk` and `sed`, which is where the defects
  and the duplication are, and cannot be unit tested.
- Kotlin scripts, Gradle or Python for the compute tier: each is a runtime or a build the project does not otherwise need
  for tooling, where Java is already required and already proven by `DpopClient`.
