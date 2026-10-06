# 0015. Which hosts a link may point to is a policy

- Status: Accepted, 2026-10-05. Its open default is closed in production by [0023](0023-production-must-decide-its-targets.md)
- Evidence: `914625f`, `TargetUrlPolicyTest`, `TargetUrlPropertiesBindingTest`, `docs/CLOUD.md`

## Problem

A shortener where anyone can create a link is an open redirector: the link carries your domain and goes anywhere. Phishers
use them for that reason. A public instance must not be one. A private instance has no such problem and should not have to
configure a list.

## Decision

- `TargetUrlPolicy` decides whether a target is accepted, after the target is parsed as an absolute `http` or `https`
  URL. `AnyTarget` accepts every host and `AllowedHosts` accepts a list, from
  `SHORTENER_SHORTLINK_TARGETURLS_ALLOWEDHOSTS`.
- An entry is a host, matched exactly, or `*.` and a domain for its subdomains but not the domain itself. Case is ignored.
- An empty list means `AnyTarget`. A list with a blank entry is refused at start, so a typo cannot accept everything or
  nothing.
- A refused target is `400`, naming the host only.
- The policy applies at creation. Links already stored keep redirecting if the list later shrinks.

## Consequences

- Good: a public demo can restrict targets to a few hosts. Tested against hosts that merely contain or end like an allowed
  one, userinfo tricks (`example.com@evil.test`) and look-alike domains.
- Good: the host comes from the parsed URI. A target with no parsable host is refused when a list is set.
- Cost: **the default is open.** An operator who forgets the variable on a public instance has an open redirector.
  [CLOUD.md](../CLOUD.md) makes it a step, and [THREAT_MODEL.md](../THREAT_MODEL.md) lists it as a finding.
- Cost: a host on the list can itself redirect elsewhere. The policy trusts the hosts it names.

## Rejected

- Not in the history: always on, with a built-in list: a private instance has no list to give.
- Not in the history: checking targets against a reputation service: a new dependency and a new place to send data, and it would make the
  service fetch URLs it is given.
