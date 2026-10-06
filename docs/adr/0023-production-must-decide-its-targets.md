# 0023. Production must decide which targets it accepts

- Status: Accepted, 2026-10-06. Amends [0015](0015-target-host-policy.md)
- Evidence: `TargetUrlPolicyConfiguration`, `TargetUrlPolicyDecisionTest`, [THREAT_MODEL.md](../THREAT_MODEL.md) F-01

## Problem

[0015](0015-target-host-policy.md) left the default open, so a public instance whose operator forgot one variable was an
open redirector, and nothing said so. A secure default that an operator must remember to set is not one.

## Decision

- Under the `production` profile the application does not start unless `allowed-hosts` has entries or `allow-any` is
  `true`. The message names both settings.
- `allow-any=true` is the explicit choice for a private instance of trusted clients. Setting it together with a list is
  refused in every profile.
- Other profiles keep the old behavior, so development and the tests need nothing.
- The check runs when the bean is created, not as a condition: the native image is built under `production`, and a
  condition would be fixed in the image for every deployment.
- The stack passes `ALLOWED_TARGET_HOSTS` and `ALLOW_ANY_TARGET` through. The rehearsal on one machine sets `allow-any`.

## Consequences

- Good: an open redirector is a line in `.env` that says so, visible in review.
- Cost: an existing deployment must add one of the two variables before it updates.
- Limit: `allow-any=true` on a public instance is still possible. Nothing can tell a public one from a private one.
