# 0035. Production does not start if it would accept bearer tokens

- Status: Accepted, 2026-10-07
- Evidence: `SecurityConfiguration.requireDpopInProduction`, `DpopRequiredInProductionTest`, `ComposeStackTest`, `DpopIntegrationTest`
- Refines [0006](0006-require-dpop-bound-tokens.md)

## Problem

[0006](0006-require-dpop-bound-tokens.md) makes DPoP the default and keeps `shortener.security.dpop.required=false` as a fallback "for
development and tests". `application-production.yaml` says `true`, and the README says the flag is not for production. But nothing
stopped it: Spring reads an environment variable after a profile's file, so `SHORTENER_SECURITY_DPOP_REQUIRED=false` on the
service in `.env` or the stack would win over the profile, and the service would accept `Authorization: Bearer` for any valid
token. A token in a log line, a proxy or a crash report would then be a stolen identity again, which is the case DPoP exists to
close, and it would be a setting nobody notices.

## Decision

- Under the `production` profile the application does not start with `dpop.required` off. The check runs when the filter chain is built,
  at run time, as the other production checks do ([0023](0023-production-must-decide-its-targets.md), [0032](0032-dpop-nonces.md)): the native build of
  [0017](0017-native-image-on-a-pinned-base.md) is made under `production` and a condition would be fixed in it for every deployment. The JVM image of
  [0036](0036-jvm-image-for-arm64.md) has no such limit, and the check is the same in both.
- The message names the setting and the variable to unset.
- Every other profile may turn it off, which is what the `test` profile does and what a developer may do on a laptop.
- `ComposeStackTest` fails if the stack sets the variable to anything but `true` on the application or the migration job.
- There is no opt-out in `production`. An instance of trusted callers that wants plain bearer tokens is not the instance the production
  profile describes: run it under another profile, and say so where it is deployed.

## Consequences

- Good: loosening the one control that makes a stolen token useless is a profile change a reviewer sees, not an environment variable.
- Cost: a deployment that relied on the flag in production stops starting until it is unset. None in this repository does.
- Cost: an operator who wants bearer tokens in production loses the production profile's other settings (JSON logs, no internals in
  errors, 5% sampling) and has to set them.
- Known: the profile's own file and the check say the same thing in two places. The file is for readers, the check is the control.

## Rejected

- An explicit `allow-bearer` switch, as [0023](0023-production-must-decide-its-targets.md) has for targets: that one is a choice about what a public
  instance is for, and this one is a hole in a control. A switch would be the setting somebody sets to make a client work.
- Removing the flag: the tests and a local `curl` depend on it, and [0006](0006-require-dpop-bound-tokens.md) says why it stays.
