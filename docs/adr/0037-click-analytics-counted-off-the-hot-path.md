# 0037. Clicks are counted in aggregate, off the redirect's path

- Status: **Proposed**, 2026-10-12. Nothing is built
- Evidence: `ClickAggregator`, `ClickFlusher`, `AnalyticsIntegrationTest`, [THREAT_MODEL.md](../THREAT_MODEL.md) E3 (R), [0011](0011-in-process-redirect-cache.md), [0012](0012-readiness-excludes-the-database.md), [0013](0013-three-database-roles-and-a-migration-job.md), [0028](0028-postgres-backups-and-a-replica.md)
- Replaces the threat model's "not recorded, by design: no analytics"

## Problem

An owner cannot tell whether a link is followed, from where, or on what kind of device. The service records nothing about a visit.
Counting them must not cost the redirect, which is almost all of the traffic and is served from memory ([0011](0011-in-process-redirect-cache.md)),
and must not turn the service into a record of who read what. The public instance is one 2 GB VM with two tasks, Keycloak and Postgres
([CLOUD.md](../CLOUD.md)), so what is added to memory and to the database has to be bounded.

## Proposal

- **Measured.** Clicks per link per hour, split by device class (`desktop`, `mobile`, `bot`). Per link per day: the referrer's host and the
  primary language of `Accept-Language`. Bots and link previewers are a class of their own, left out of the default total.
- **Not measured.** The address, the raw `User-Agent`, cookies, any visitor identifier, the country, unique visitors. Nothing that identifies
  a visitor is stored or logged by this feature.
- **Capture.** The controller publishes `LinkFollowed` once `resolve` has succeeded for a `GET`, so a `HEAD` (what link checkers send), a `404` or a `410`
  counts nothing and an unknown code cannot make a row. The service's `resolve` does not change: headers are the web layer's business. Values are closed types (`DeviceClass`,
  a referrer that is `Direct` or a `Host`), not nullable strings.
- **Aggregate in memory, write in batches.** A bounded aggregator keyed by (code, hour, class, dimension, value) is flushed about every 10 s with
  `INSERT ... ON CONFLICT DO UPDATE SET clicks = clicks + EXCLUDED.clicks`, so rows grow with links and hours, not with clicks, and replicas add up
  without coordinating. The referrer values are the top 20 per link and day, the rest `other`, so a forged `Referer` cannot grow the table.
- **At most once.** A full aggregator drops and counts what it drops. A flush that cannot reach Postgres keeps its batch up to the bound and then drops;
  it never reaches readiness ([0012](0012-readiness-excludes-the-database.md)) and a redirect served from a stale copy still counts. A restart loses
  what was not flushed, and shutdown flushes first.
- **Storage.** Two tables in migration V7. The application role gets `SELECT`, `INSERT` and `UPDATE (clicks)` on them and no `DELETE`, stated next to
  the schema like V6 ([0013](0013-three-database-roles-and-a-migration-job.md)).
- **Reading.** `GET /api/short-links/{shortCode}/stats?from=&to=&granularity=hour|day`: the total, the series, the top referrers, devices and languages.
  The owner and an administrator may read it, through `ManageableLinks`, so another client's link is a `404`. The range is bounded.
- **Retention.** Hours for 90 days, days for 2 years. A job under its own role, which may `DELETE` from these tables only, folds hours into days and
  removes what is old, so `shortener_app` still cannot delete. No partitioning ([0028](0028-postgres-backups-and-a-replica.md)). A monthly compressed
  archive waits for a remote destination: on a volume of the same node it would be lost with the node, as the backup repository is.
- **Switch.** `shortener.analytics.enabled`. Off, a null object counts nothing and the stats answer zeros, as [0011](0011-in-process-redirect-cache.md) does for the cache.
- **Modules.** `analytics` depends on `shortlink` for the event and for the ownership rule, never the reverse. The module tests check it.
- **Observed.** `analytics_clicks_recorded_total`, `analytics_clicks_dropped_total`, `analytics_flush_seconds` and the aggregator's size. An alert on drops.

## Consequences

- Good: the redirect pays one event on a bounded queue, with no database read or write. The cost of the feature is measured in rows per link and hour.
- Good: no personal data is kept, so there is nothing to disclose or erase for a visitor.
- Cost: the figures are approximate. A crash, a full aggregator or a database outage loses clicks, and a bot that sends a browser's `User-Agent` counts as one.
  Do not use them to bill, to detect fraud or to settle a dispute.
- Cost: a client can inflate its own link's count, bounded by the per-address limit of 300 a minute ([0008](0008-rate-limits-per-instance-in-memory.md)).
- Cost: the aggregator's memory is a setting to size for the 2 GB VM, and its entries are lost on a restart.
- Cost: a new migration, a role in `bootstrap.sql`, a job to run, and the docs that said "no analytics" change: the threat model, the README, the UI plan and the API description.
- Known: a referrer host can name an internal site. Only the host is kept, never the path or the query.
- Way back: set `enabled` to false. The tables stay and are read-only history.

## Rejected

- A row per click: a write on the hot path, a table that grows with traffic, and a record of when and from where each visitor came.
- Spring Modulith's event publication registry: it persists each event, which is the write per click. Its table was dropped by V4 and nothing publishes events now, so
  the event here is a plain in-memory one ([0003](0003-modules-and-ports-checked-by-tests.md) has the modules).
- Redis counters or a shared aggregator: an operational dependency for a number that may be approximate ([0011](0011-in-process-redirect-cache.md) rejects it for the same reason).
- Counting inside the redirect cache: a hit would count and a miss would not, or the reverse, and the cache is meant to be replaced or turned off.
- Not in the history: reading the logs. They carry client addresses ([0024](0024-logging-and-audit.md)), so analytics would depend on keeping what this feature does not want.
- Not in the history: a hosted analytics service. It would receive every visitor's request, which is what the service avoids holding itself.
- Deferred, not rejected: the country (it needs a GeoIP database kept up to date and the address handled in memory) and unique visitors (with replicas and no cookie they
  are an estimate that comes close to identifying someone).

## Open

- Whether `enabled` is on in the `production` profile or an instance opts in.
- The panel in the UI waits for [0030](0030-web-ui-as-a-static-spa.md).
- The archive's destination, S3 or SFTP, as in [0028](0028-postgres-backups-and-a-replica.md).
