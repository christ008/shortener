# 0027. The tools run on JDK 25, the client people outside the project run stays on 17

- Status: Accepted, 2026-10-06. Refines [0026](0026-scripting-standard.md), which put every Java script on the JDK 17 baseline
- Evidence: `tools/`, `gradle/tooling.gradle.kts`, `RealmsTest`, `ToolingTasksTest`, `DpopClient.java`

## Problem

[0026](0026-scripting-standard.md) put the compute tier on one source file for JDK 17, because `DpopClient.java` had to run on
whatever JDK a stranger has. That is right for the client and wrong for the rest:

- One source file cannot use another before JDK 22, so a helper is copied or a file grows. `DpopClient` is 781 lines, and
  `Smoke` could not reuse its JSON or its signing.
- Every `java File.java` compiles first and costs about a second (measured: 1.03, 1.05, 1.04 s). `smoke.sh` starts the client
  about sixteen times.
- Each file carries its imports and a class around `main`.
- The people who run the other tools already have JDK 25, because the project does not build without it.

## Decision

Two baselines, by where the file lives.

| Where | Run by | JDK | Shape |
|---|---|---|---|
| `deploy/keycloak/DpopClient.java` | anyone who calls the API, with no clone of the build | 17 | one file, as before |
| `tools/` | whoever builds, tests or operates the project | 25 | several files in one directory |

- **Tools are compact source files** (JEP 512, final in 25): `void main(String[] args)`, no class around it, `java.base`
  imported without asking, and `IO.println`. A helper that several tools use is an ordinary class in its own file beside them
  (JEP 458, the launcher compiles what the file refers to), and may use `import module` (JEP 511).
- **`tools/run TOOL` starts a tool**: it finds a JDK 25 and says so when there is none, and `deploy/keycloak/dev-setup` and
  `perf/smoke.sh` are three-line shims of it, which keeps the paths the workflows and the docs use.
- **`tools/DpopClient.java` is a link to the client** in `deploy/keycloak/`, because the launcher looks for other source files only
  beside the one it starts. The client gained a small public API (`DpopClient.call`, `DpopClient.token`) that `Smoke` uses, and
  stays one file for 17 with its command line as it was.
- **`tools/Cli.java` is the shared part**: one exit-status convention (0 done, 1 could not with one line on stderr, 2 usage), JSON
  string escaping, whole-file reads and atomic writes. A second copy of any of it is a defect.
- **The Java is the plain kind** that [javaevolved.dev](https://javaevolved.dev/) lists: `var`, records, switch expressions and
  patterns, text blocks, `Files.readString`, `Stream.toList`, `ProcessBuilder` with an argument array, `HttpClient`.
- **The door is Gradle**, which starts a tool with the project's toolchain JDK 25 and sets `JAVA_HOME` for the scripts it runs.
  A tool run by hand needs `java` 25 first on the PATH, and a JDK 21 answers a compact file with a message about a preview
  feature, which says nothing useful. `dev-setup` therefore checks the version and says what is needed.
- **Tests**: a tool runs as a subprocess on the JDK that runs the tests, and all of `tools/` compiles together with `--release 25
  -Xlint:all -Werror`. `DpopClient` keeps its own compile for 17 and runs on a 17 in CI.

## Consequences

- Good: shared code instead of copies, shorter files, and one process for what `smoke.sh` runs as sixteen.
- Good: `Realms` wrote the same bytes as before the move (compared on real keys), and `Smoke` ran its 21 checks against a live
  instance in 2.4 s where `smoke.sh` took 16.1 s.
- Cost: a symbolic link in the tree, which a checkout on Windows without link support turns into a file with a path in it.
- Cost: two baselines, told apart by directory. A tool that a stranger must run belongs in `deploy/keycloak/` and on 17.
- Cost: JDK 25 for `dev-setup`, which asked for 17, and which no longer needs `openssl`, `awk` or `sed` for its work. The build already asks for it, and CI's `stack` job moved to 25.
- Cost: a compact file is a class nothing else can refer to, so a tool is tested through its command line, as every one already was.
- Not decided: a Gradle task to lint the Java tools on their own, and whether to keep shell for the `perf` scripts that only start
  `docker` and `k6`.

## Rejected

- Everything on 17: the status quo, with the copies, the second per call and the files that only grow.
- Everything on 25, the client included: a stranger who wants to try the README would need a JDK that most distributions do not
  package yet. The client is the one file where that matters, and it is one.
