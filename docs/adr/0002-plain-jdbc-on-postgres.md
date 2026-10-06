# 0002. Plain JDBC on Postgres, not JPA

- Status: Accepted, 2026-10-03
- Evidence: `b2873df`, `JdbcShortLinkRepository`, `ShortLinkSchemaTest`, `ShortCodeRaceIntegrationTest`

## Problem

Two statements carry the service: claim a code, and look a link up by it. Claiming must be atomic, so that exactly one
of any number of concurrent inserts for a code wins. Listing must sort codes the same way on every database.

## Decision

- `JdbcClient` with the SQL written out, behind the `ShortLinkRepository` interface.
- `INSERT ... ON CONFLICT DO NOTHING RETURNING` claims a code and reads the row back in one statement. The database
  assigns `created_at`.
- `CHECK` constraints mirror the domain (code format, `http` or `https`), and a test fails if schema and Kotlin disagree.
- Sorting uses `COLLATE "C"`, so the order does not depend on the database's locale.
- Only the persistence package knows SQL.

## Consequences

- Good: no check-then-insert race, no retry on a constraint violation, no ORM proxies or reflection mapping, which also
  keeps the native image smaller ([0017](0017-native-image-on-a-pinned-base.md)).
- Good: the SQL is what runs, so it can be explained, tested for its plan and granted privileges for
  ([0013](0013-three-database-roles-and-a-migration-job.md)).
- Cost: mapping and paging are ours to write and keep correct.
- Security: every value reaches SQL as a bound parameter. The one dynamic part, `ORDER BY`, is built from a whitelist
  that maps a property to a column.

## Rejected

- JPA with Spring Data: hides the two statements that matter and needs entities, proxies and reflection hints.
