# 0020. The DPoP client is Java for JDK 25, in a few files that run without a build

- Status: Accepted, 2026-10-06, and amended 2026-10-18 twice: the baseline is JDK 25, not 17; and the client is several files, not one (see the amendment below). Supersedes the shell client of [0019](0019-no-secrets-in-git.md)
- Evidence: `a412d3a`, `deploy/keycloak/DpopClient.java`, `DpopClientTest`

## Problem

[0006](0006-require-dpop-bound-tokens.md) makes every client able to sign, so the repository must show how. The shell
client needed only `openssl` and `curl`, but it had to build DER, JWK and JSON by hand to sign ES256 and to read keys. A
security-relevant client made of string handling is hard to get right and to test.

## Decision

- `deploy/keycloak/DpopClient.java` and the classes beside it, run as `java deploy/keycloak/DpopClient.java ...` with no build step. The JDK already
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

- Good: tested as it is run, and keys that the JDK parses rather than a script.
- Cost: a client needs a JDK 25, where the shell one needed `openssl` and `curl`. `openssl` is still used for passwords.
- Cost: 781 lines of security-relevant code to keep correct, and a second JDK in CI.
- The client runs on the user's machine, not in the stack.

## Amendment, 2026-10-18: several files

The client grew into one class of 1036 lines with about 30 dependencies on other classes: the command line, the keys, the DPoP
proofs, the token endpoint and the API calls, the browser login, and a JSON reader and writer. SonarCloud reports it as a
"monster class" (S6539: at most 20 dependencies), and every change to one part touches the whole file, so its tests and its
review cover everything at once. That is high complexity and coupling, and a single file is what caused both.

The client is now several files in `deploy/keycloak/`, in the default package, one top-level type each:

- `DpopClient.java`: the command line, the commands and their output.
- `TokenEndpoint.java`: the token endpoint, the nonces of each server, and the API calls.
- `KeycloakLogin.java`: the browser part of the login.
- `Keys.java`, `Jose.java`: the key material, and the JWS and DPoP proofs.
- `Http.java`, `Oauth.java`: the transport helpers, and the names of the protocol.
- `Json.java`, `Console.java`, `SystemConsole.java`, `Failure.java`, `Refused.java`: the JSON, the output and the errors.

It still runs with `java deploy/keycloak/DpopClient.java ...` and no build: JDK 25's source launcher compiles the other files it
uses from the same directory (JEP 458). The rule that stays is the one behind the single file: no dependency beyond the JDK.

- Cost: more files to read, and the build and `DpopClientTest` compile the whole directory with `--release 25` and every lint on.
- Cost: the launcher's multi-file mode is the JDK 25 one; the client does not run on a JDK that lacks it, which is the baseline anyway.
- The Kotlin twin in `tools/` is unchanged and still one JAR.

## Rejected

- The shell client: see the problem. It is in the history (`0f4c18d`) if a runtime-free client becomes the priority.
