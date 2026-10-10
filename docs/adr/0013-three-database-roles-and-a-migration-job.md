# 0013. Three database roles, and migrations in their own job

- Status: Accepted, 2026-10-05
- Evidence: `c58834c`, `DatabaseRolesTest`, `MigrationConventionsTest`, `ComposeStackTest`, `deploy/postgres/bootstrap.sql`

## Problem

The application connected as the owner of the tables. An injection, or a compromised dependency, could drop or rewrite
every link, and a rewritten target turns every short link into a phishing link.

## Decision

- `shortener_migrator` owns the tables and runs Flyway. `shortener_app` serves requests: `SELECT` and `INSERT` on
  `short_link`, `UPDATE` only of `disabled_at` and `disabled_by`, and read of the Flyway history. No `DELETE`, no
  `TRUNCATE`, no change to a target or a code, no DDL. `shortener_exporter` has `pg_monitor`: statistics, not data.
- The grants are stated in migration V6, next to the schema. Where the role does not exist, V6 does nothing.
- Limits on the application role apply at login, so no application setting lifts them: `statement_timeout` 5 s,
  `lock_timeout` 2 s, `idle_in_transaction_session_timeout` 10 s.
- Migrations run in a one-shot `migrate` service with `SHORTENER_MIGRATE_ONLY=true`. Only that service gets the
  migrator's password, which `ComposeStackTest` checks.
- A rollout migrates first, then updates the application. Migrations must therefore work with the previous version:
  add, then switch, then remove. `MigrationConventionsTest` rejects what held locks on two million rows.
- The application still runs Flyway on start, as `shortener_app`, and finds nothing to apply. If the schema is behind it
  cannot change it and fails to start.

## Consequences

- Good: code execution in the application cannot change a target, delete a link or alter the schema. It can still
  disable links and insert new ones.
- Good: a stuck query or abandoned transaction cannot pin one of ten connections.
- Cost: a bootstrap script run once as a superuser, a job in every deploy, and the rule to write compatible migrations.
- Cost: nothing re-enables a link. An operator must do it as the owner.

## Rejected

- One role: simplest, and the one that makes a SQL injection fatal.
- Flyway switched off in the application image: the native image decided its beans at build time, so it could not be ([0017](0017-native-image-on-a-pinned-base.md)).
  The JVM image of [0036](0036-jvm-image-for-arm64.md) could, and it stays on because the application then fails to start on a schema that is behind.
