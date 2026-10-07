# 0011. An in-process cache of active links

- Status: Accepted, 2026-10-03
- Evidence: `56db1fe`, `RedirectCacheTest`, `perf/results/2026-10-03-cache`, `INTERNALS.md#redirect-cache`

## Problem

Every redirect cost a database read, and the benchmarks showed the pool of ten saturating before the CPU did. Redirects
are 99% of the traffic.

## Decision

- A Caffeine cache per instance in front of the lookup that redirects use, and only that one. `get`, `list` and `disable`
  read the repository, so the checks that decide who may see or change a link never see a stale one.
- Active links only. An unknown code is never cached, so a new link works at once. A disabled link is never cached, so
  a takedown is not extended.
- TTL 30 s, 100,000 entries. The instance that disables a link evicts it at once, the others stop serving it when their
  entry expires.
- Concurrent misses for one code share one load.
- `enabled: false` swaps in `NoRedirectCache`, a null object ([0004](0004-absence-is-a-type.md)).
- The redirect is `302`, not `301`. A browser keeps a `301` indefinitely and would keep following a link after a takedown,
  which the 30 s window above would not bound. A `302` costs one more request per visit.

## Consequences

- Good: redirect p99 of 1 ms at 5,000 requests a second with no failures on the JVM, and the native image no longer
  fails there.
- Cost: **a takedown reaches every instance within 30 s, not at once.** For an abuse report that is the window in which
  a bad link keeps working. It is the number to lower if takedown speed matters more than the database.
- Cost: the first miss for a disabled or unknown code reads twice.

## Rejected

- Redis or a shared cache: an operational dependency and a network hop for a problem one process solves.
- Invalidation across instances with `LISTEN/NOTIFY`: more machinery and a new failure mode for bounded staleness.
- Caching `404` and `410`: delays new links and takedowns.
