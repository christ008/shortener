# 0012. Ready without the database, and keep redirecting known links

- Status: Accepted, 2026-10-05
- Evidence: `d28033b`, `fff5a1f`, `StorageUnavailableIntegrationTest`, `DatabaseLimitsTest`, `INTERNALS.md#redirect-cache`

## Problem

Readiness included the database. Every instance shares one, so an outage took them all out of rotation at once, and a
health check that restarts unhealthy containers would have restarted all of them. Removing them gained nothing.

## Decision

- Readiness is the application's own state. An instance answers what it can and gives `503` with `Retry-After: 5` for
  the rest. A rollout stays safe because a new instance starts only after the migration job reached the database
  ([0013](0013-three-database-roles-and-a-migration-job.md)).
- Storage that cannot serve a request is `StorageUnavailableException`: no connection, a statement cut off at 5 s, or a
  lock given up after 2 s. Any other database error is not mapped, so a bug is not hidden behind a retry.
- When the database cannot be reached and an entry has expired, the cache serves the last copy this instance read, for up
  to `stale-if-error` (5 minutes, `0` turns it off). Unread codes get `503`. A link the database then reports gone or
  disabled is dropped.
- Serving a stale copy increments `shortlink_redirect_cache_stale_total`, which should be zero and has an alert.

## Consequences

- Good: a database outage degrades creation and cold reads, not the redirects people already depend on. Drilled on the
  native binary with Postgres stopped.
- Cost: **a link disabled elsewhere just before an outage keeps redirecting here for up to 5 minutes.** That is why the
  window is short, and it cannot be shorter than the TTL.
- Cost: an instance can be unable to serve most traffic and still be reported ready.

## Rejected

- Readiness that includes the database: restarts everything during an outage, which is the thing that makes it worse.
- Serving stale without a limit: turns a takedown into a suggestion.
