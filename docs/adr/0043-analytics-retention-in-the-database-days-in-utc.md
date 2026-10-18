# 0043. The retention of the click counts runs in the database, folds days into months kept for ever, and days are UTC days

- Status: Accepted, 2026-10-18
- Evidence: `V9__analytics_retention.sql`, `V10__analytics_retention_indexes.sql`, `deploy/postgres/bootstrap.sql`, `JdbcRetentionStoreTest`,
  `JdbcClickStatsRepositoryTest`, `AnalyticsRetention`, `DatabaseRolesTest`, `MigrationConventionsTest`, `DefaultClickStatsServiceTest`, `alerts.test.yml`
- Refines [0037](0037-click-analytics-counted-off-the-hot-path.md)

## Problem

[0037](0037-click-analytics-counted-off-the-hot-path.md) keeps hours for 90 days and days for 2 years, done by "a job under its own role".
Four things were open when it was built:

- Who runs that job. The plan was a Kotlin tool in `tools/`, but the node has no JDK, and every other scheduled task there is a cron line
  ([DEPLOY.md](../DEPLOY.md)) that has to be installed and watched per server.
- Which day a day is. A day folded from hours cannot be split again, so the choice is permanent for the data older than 90 days.
- Whether what is older than 2 years must go, or can be kept small.
- What a read of a window that is no longer kept answers.

## Decision

- **Days are UTC days**, in the API and in storage, as the hours have always been instants. A client that wants days in its own time zone
  groups the hourly series, which covers the last 90 days. The referrer and language counts are per UTC day too, so the series and the
  breakdown cover the same days.
- **Three tiers, nothing deleted.** Hours for 90 days (`link_click_hourly`); then days for 730 (`link_click_daily_device`, and
  `link_click_daily` for the referrer and the language); then months, kept for ever: `link_click_monthly_device` holds one row per link,
  month and class with an array of 31 daily counts, and `link_click_monthly` the referrer and language summed over the month. A day is in
  exactly one tier.
- **Smaller by shape, not by compression.** Measured on Postgres 18.6 with a year of 1,000 links: daily rows took 49 MB with their indexes,
  monthly rows with an array 5.9 MB, because the row header and the index entry are paid once a month. TOAST's `lz4`, even with
  `toast_tuple_target = 128`, compressed none of those rows: they are about 150 bytes. The backups compress, and go off the node.
- **Two views for reading.** `link_click_day` gives clicks per link, day and class wherever the day is kept, and `link_click_breakdown`
  the referrer and language per period, a day or a month. Both are `security_invoker`, so they give no one more than the tables do. The
  application reads windows of days and breakdowns from them; windows of hours read the hours.
- **The rules live in one function, `analytics_retention()`** (V9), `SECURITY DEFINER`, with its search path fixed and no argument. A call
  folds the oldest UTC day of hours older than 90 days into days, and the oldest day older than 730 into its month, in the same statement
  that removes it. One day of each a call keeps a call within the application role's 5 s statement timeout ([0013](0013-three-database-roles-and-a-migration-job.md)).
- **The function belongs to `shortener_retention`**, a role made by `bootstrap.sql` that cannot log in and holds exactly what the function
  does: read and move the counts down the tiers, no `DELETE` on the months, nothing on `short_link`. It has `CREATE` on the schema, which a
  new owner of a function needs; the only code that runs as it is the function, which builds no statement from text. Where the role does
  not exist, as in a developer's database with one user, the function stays with the migrator.
- **The application calls it.** `EXECUTE` is revoked from `PUBLIC` and granted to `shortener_app` only. `AnalyticsRetention` calls it a minute
  after the start and then every 24 hours, step after step until a step finds nothing, at most 1,000 steps a run. Every instance does; a
  transactional advisory lock lets one at a time work, and the others report busy. It runs also when counting is switched off.
  `shortener.analytics.retention.enabled=false` stops an instance from calling it.
- **A window of hours older than 90 days is a `400`** that says to ask by day, rather than zeros that look real. Days are kept for ever, so a
  window of days is never refused for its age. A breakdown that reaches the months covers them whole, and its start and end say so.
- **The indexes it needs are built concurrently** (V10), so building them does not block the flushes. That needs Flyway to hold its lock
  outside a transaction (`spring.flyway.postgresql.transactional-lock: false`): `CREATE INDEX CONCURRENTLY` waits for every open transaction,
  and Flyway's own never ends while it waits. `MigrationConventionsTest` fails if a concurrent index is added without that setting.
- **Two alerts.** `ShortenerClicksDropped` when clicks are dropped for ten minutes, by reason, and `ShortenerAnalyticsRetentionFailing` when
  retention failed and no instance completed it for a day.

## Consequences

- Good: no cron line, no role with a password, no job to deploy. Retention works wherever the application runs, from a laptop to the stack.
- Good: the application role still has no `DELETE`, and no role at all can delete a monthly count. A compromised application can at most
  run retention early, which moves only what is old enough and loses nothing.
- Good: every day ever counted can be read, by the day, from about 6 MB a year for 1,000 active links.
- Good: the API never shows a zero for a bucket it no longer keeps.
- Cost: the counts grow for ever, slowly. Deleting old months is a migration away.
- Cost: days in a time zone are the client's work, and only for the last 90 days. A day in a zone whose offset is not whole hours (India,
  Nepal) cannot be built from UTC hours at all. Older than 730 days, the referrer and language are by the month.
- Cost: the rules are in SQL, not in the application, and changing a period is a migration. A fourth role, which a database set up before
  V9 gets by running `bootstrap.sql` again ([DEPLOY.md](../DEPLOY.md#update-roll-back)).
- Known: the 90 days are written twice, in the function and in `Granularity.Hour`'s `Kept.For`, which the service checks windows against.
  Both count from the start of the UTC day, and `JdbcRetentionStoreTest` and `GranularityTest` pin both.

## Rejected

- Deleting days after 730 days, as [0037](0037-click-analytics-counted-off-the-hot-path.md) planned: months cost an eighth of the days, so
  keeping them is cheaper than the history they hold is worth losing.
- A materialized view as the cold tier: it holds no data of its own and is computed again from its tables, so once retention moves a day out
  of them, the next `REFRESH` loses it. Checked: half a year deleted from the source, refreshed, and the view had none of it. Not used either
  to speed up reads: every read is one link over a range of a primary key.
- Compression in the database: TOAST compresses values over about 2 KB, and a row of counts is about 150 bytes. Columnar compression needs an
  extension (TimescaleDB, Citus) in the image, for a table this small.
- A `tz` parameter on the reads: it can only rebuild days from hours, so within 90 days and for whole-hour offsets, and the breakdown would
  still be in UTC days, so the two would disagree.
- Keeping hours for 2 years so any zone can be rebuilt: up to 24 times the rows for the old data, on a 2 GB node, for a view few will ask for.
- A cron line on the node calling the function, as the backups do, or a job from the same image under its own role, as the migrations are:
  one more thing to install and watch per server, or a role with a password, a secret and a service, for one statement a day.
- A Kotlin tool in `tools/`: the node has no JDK.
- The migrator as the function's owner: it owns every table, `short_link` included, so a fault in the function would reach them.
