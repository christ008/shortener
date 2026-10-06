# 0008. Rate limits are token buckets in memory, per instance

- Status: Accepted, 2026-10-03
- Evidence: `4c62308`, `RateLimiter`, `IpRateLimitIntegrationTest`, `ClientRateLimitIntegrationTest`

## Problem

Someone will guess tokens, hammer the redirect, or flood creates. The service needs a ceiling on what one address or one
client can ask, without a new component to run.

## Decision

- Bucket4j token buckets in a size-bounded Caffeine cache that expires idle keys, so a flood of distinct keys cannot
  exhaust memory.
- Two filters. Per IP before authentication, 300 a minute, which also throttles token guessing. Per client after it, 60 a
  minute.
- Actuator paths are never limited, so health checks cannot be starved.
- Answered as `429` with `Retry-After`, as a problem detail ([0009](0009-problem-details-for-every-error.md)).
- The client address is the one Tomcat builds from the forwarded headers, which the edge replaces and the application
  trusts only from private addresses ([0016](0016-compose-and-swarm-instead-of-kubernetes.md)).

## Consequences

- Good: no dependency, no network hop, nothing to fail on the hot path.
- Cost: state is per instance, so the real limit is the limit times the replicas, and a restart forgets it.
- Cost: per-address limits are weak against many addresses. They are a floor, not a defence against a distributed flood.
- Not covered: a quota. A client within 60 requests a minute can still create unbounded links over time.

## Rejected

- Redis or another shared store: gives a global limit for an operational dependency and a network hop on every request.
  Revisit if replicas multiply or abuse needs one number.
- Not in the history: limiting only at the edge: nginx caps connections and body size ([0016](0016-compose-and-swarm-instead-of-kubernetes.md))
  but does not know the client, which exists only after authentication.
