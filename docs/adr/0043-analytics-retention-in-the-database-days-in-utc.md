# 0043. The retention of the click counts runs in the database, called by the application, and days are UTC days

- Status: Accepted, 2026-10-17
- Evidence: `V9__analytics_retention.sql`, `V10__analytics_retention_indexes.sql`, `JdbcRetentionStoreTest`, `AnalyticsRetention`,
  `DatabaseRolesTest`, `MigrationConventionsTest`, `DefaultClickStatsServiceTest`, `alerts.test.yml`
- Refines [0037](0037-click-analytics-counted-off-the-hot-path.md)

## Problem

[0037](0037-click-analytics-counted-off-the-hot-path.md) keeps hours for 90 days and days for 2 years, done by "a job under its own role".
Three things were open when it was built:

- Who runs that job. The plan was a Kotlin tool in `tools/`, but the node has no JDK, and every other scheduled task there is a cron line
  ([DEPLOY.md](../DEPLOY.md)) that has to be installed and watched per server.
- Which day a day is. A day folded from hours cannot be split again, so the choice is permanent for the data older than 90 days.
- What a read of a window that was deleted answers.

## Decision

- **Days are UTC days**, in the API and in storage, as the hours have always been instants. A client that wants days in its own time zone
  groups the hourly series, which covers the last 90 days. The referrer and language counts are per UTC day too, so the series and the
  breakdown always cover the same days.
- **The rules live in one function, `analytics_retention()`** (V9), `SECURITY DEFINER`, owned by the migrator, with its search path fixed and
  no argument. A call folds the oldest UTC day of hours older than 90 days into `link_click_daily_device` and deletes those hours, and deletes
  the oldest day older than 730 days from each daily table. One day of each a call keeps a call within the application role's 5 s statement
  timeout ([0013](0013-three-database-roles-and-a-migration-job.md)).
- **The application calls it.** `EXECUTE` is revoked from `PUBLIC` and granted to `shortener_app` only. `AnalyticsRetention` calls it a minute
  after the start and then every 24 hours, step after step until a step finds nothing, at most 1,000 steps a run. Every instance does; a
  transactional advisory lock lets one at a time work, and the others report busy. It runs also when counting is switched off, because what
  was counted still has to go. `shortener.analytics.retention.enabled=false` stops an instance from calling it.
- **A window older than what is kept is a `400`.** Hours are kept from the start of today, in UTC, less 90 days, and days less 730; a window
  that starts earlier is refused with a message that says so, rather than answered with zeros that look real. A window of days reads the
  hours and the folded days; a day is in one table or the other, never both.
- **The indexes it needs are built concurrently** (V10), so building them does not block the flushes. That needs Flyway to hold its lock
  outside a transaction (`spring.flyway.postgresql.transactional-lock: false`): `CREATE INDEX CONCURRENTLY` waits for every open transaction,
  and Flyway's own never ends while it waits. `MigrationConventionsTest` fails if a concurrent index is added without that setting.
- **Two alerts.** `ShortenerClicksDropped` when clicks are dropped for ten minutes, by reason, and `ShortenerAnalyticsRetentionFailing` when
  retention failed and no instance completed it for a day.

## Consequences

- Good: no cron line, no role with a password, no job to deploy. Retention works wherever the application runs, from a laptop to the stack.
- Good: the application role still has no `DELETE`. It can delete click counts only through the function, by its fixed rules; a compromised
  application can at most run retention early, which deletes nothing that was kept.
- Good: the API never shows a zero for a deleted bucket.
- Cost: days in a time zone are the client's work, and only for the last 90 days. A day in a zone whose offset is not whole hours (India,
  Nepal) cannot be built from UTC hours at all.
- Cost: the retention rules are in SQL, not in the application, and changing a period is a migration.
- Cost: the first concurrent index needed a Flyway setting that no migration had needed before.
- Known: the 90 and 730 days are written twice, in the function and in `Granularity.kept`, which the service checks windows against. Both are
  counted from the start of the UTC day, and `JdbcRetentionStoreTest` and `GranularityTest` pin both.

## Rejected

- A `tz` parameter on the reads: it can only rebuild days from hours, so within 90 days and for whole-hour offsets, and the breakdown would
  still be in UTC days, so the two would disagree.
- Keeping hours for 2 years so any zone can be rebuilt: up to 24 times the rows for the old data, on a 2 GB node, for a view few will ask for.
- A cron line on the node calling the function, as the backups do: one more thing to install and watch per server, for the same function.
- A job from the same image under its own role, as the migrations are: a role with a password, a secret and a service, for one statement a day.
- A Kotlin tool in `tools/`, as [0037](0037-click-analytics-counted-off-the-hot-path.md) planned: the node has no JDK.
- Answering zeros for a deleted window, with a field that says where the kept data starts: a chart would draw the zeros.
