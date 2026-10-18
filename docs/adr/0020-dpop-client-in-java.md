# 0020. The DPoP client is one Java file, for JDK 25

- Status: Accepted, 2026-10-06, and amended 2026-10-18: the baseline is JDK 25, not 17. Supersedes the shell client of [0019](0019-no-secrets-in-git.md)
- Evidence: `a412d3a`, `deploy/keycloak/DpopClient.java`, `DpopClientTest`

## Problem

[0006](0006-require-dpop-bound-tokens.md) makes every client able to sign, so the repository must show how. The shell
client needed only `openssl` and `curl`, but it had to build DER, JWK and JSON by hand to sign ES256 and to read keys. A
security-relevant client made of string handling is hard to get right and to test.

## Decision

- `deploy/keycloak/DpopClient.java`, run as `java deploy/keycloak/DpopClient.java ...` with no build step. The JDK already
  signs in the JWS format and reads P-256 keys, so nothing is hand-made. It also makes the dev keys (`keygen`).
- Written for the JDK 25 baseline (amended 2026-10-18; it was 17): records, sealed types, text blocks, switch expressions and
  unnamed variables, nothing newer.
- A strict JSON reader and writer, escaped claims, a timeout on every request, a refused login or token endpoint reported
  in one line with exit status 1, and an `error` in a redirect reported as one.
- `DpopClientTest` runs the real file, plays a single-page app's login against its stand-in and compiles with
  `--release 25` and every lint on. CI runs it on a JDK 25. `dev-setup` checks for the JDK and says so.
- Since 2026-10-18 there is also a Kotlin client of the same behaviour, `tools/.../dpop/DpopClient.kt`, packaged as
  `dpop.jar` and published as a CI artifact. `dpopJarParity` runs the same suite against it, so the two cannot drift apart.
- The generated dev keys and passwords stay as [0019](0019-no-secrets-in-git.md) made them.

## Consequences

- Good: one source file, tested as it is run, and keys that the JDK parses rather than a script.
- Cost: a client needs a JDK 25, where the shell one needed `openssl` and `curl`. `openssl` is still used for passwords.
- Cost: 781 lines of security-relevant code to keep correct, and a second JDK in CI.
- The client runs on the user's machine, not in the stack.

## Rejected

- The shell client: see the problem. It is in the history (`0f4c18d`) if a runtime-free client becomes the priority.
