# 0010. Virtual threads, and a bound on connections

- Status: Accepted, 2026-10-03 and 2026-10-04
- Evidence: `1e27e3a`, `be98467`, `VirtualThreadsIntegrationTest`, `ConnectionLimitsTest`, `INTERNALS.md#performance`

## Problem

Each request does one blocking query. A platform thread per request caps concurrency at the thread pool. Then the
native image lost its heap at 5,000 requests a second: no leak, but every open connection holds about 150 KB of Tomcat
buffers, and a slightly slow server makes an open-model client open more of them until memory ends.

## Decision

- `spring.threads.virtual.enabled`: Tomcat serves each request on a virtual thread, on both ports. The Hikari pool of ten
  connections, with a 3 s wait, is the real concurrency limit for the database.
- `server.tomcat.max-connections` 500 and `accept-count` 100. The rest are refused.
- Storage that cannot serve a request is `503` with `Retry-After`, not `500`
  ([0012](0012-readiness-excludes-the-database.md)).

## Consequences

- Good, measured at 12,000 requests a second, 2.4 times what two cores sustain: memory 208 MiB instead of 514 MiB,
  longest GC pause 60 ms instead of 2.6 s, redirect p99 1 ms instead of 4 s, with about 1% of requests refused.
- Good for security: a ceiling on connections is also a ceiling on what a slow-client attack can hold.
- Cost: beyond capacity the service sheds load and creates still queue for seconds. A limit is protection, not capacity.
- The bound must stay inside the heap: connections times 150 KB.

## Rejected

- Coroutines or reactive: nothing to fan out, one blocking call per request. The problem was never scheduling.
- Raising the limit: connection limits of 8192, 1000, 500 and 250 gave the same results at 5,000 requests a second, and
  only the lower ones survived overload.

## Update, 2026-10-09

Measured again on the current image, at 12,000 requests a second (1.5 times its ceiling of 8,000): without the limit (8192) the heap reached the container's 512 MiB, three
`OutOfMemoryError`s were logged, 23.4% of the time went to GC (longest pause 2.8 s) and 16.6% of the requests failed; with 500, memory peaked at 271 MiB, GC took 2.6%
(longest pause 107 ms) and 0.6% failed. The decision stands. See [Performance](../INTERNALS.md#overload-and-the-connection-limit).
