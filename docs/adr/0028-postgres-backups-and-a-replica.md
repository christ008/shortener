# 0028. Backups with pgBackRest and a replica, and no partitioning yet

- Status: Accepted, 2026-10-06
- Evidence: `compose.prod.postgres-ha.yaml`, `deploy/postgres/`, `ComposeStackTest`, [THREAT_MODEL.md](../THREAT_MODEL.md) E5, [0013](0013-three-database-roles-and-a-migration-job.md)

## Problem

Every link, and Keycloak's realm, lives on one disk of one node. Losing the volume loses all of it, and losing the node stops
creating links until someone rebuilds the database. [0014](0014-offset-pagination-with-totals.md) measured that the table is small enough
(a deep page at two million links costs 0.3 s) that a faster database was never the need. The need is to **not lose it, and to get it back**.

## Decision

An optional overlay, `compose.prod.postgres-ha.yaml`, for the two things that are missing, and a record of the one that is not wanted.

- **Backups with pgBackRest.** The image is the official one plus the package (`deploy/postgres/Dockerfile`). The primary archives
  its WAL, at least every five minutes (`archive_timeout=300`), so a restore loses at most that. Retention is two full backups and
  the differentials between them. Nothing schedules the backups: `deploy/postgres/backup` does it from the manager's cron, with `init`,
  `full`, `diff`, `check`, `info` and `restore-test`.
- **The repository is a local volume, for now.** It protects against a corrupt table, a bad migration and a mistaken `DELETE` as an
  owner, and not against losing the node. A deployment that needs that sets `repo1-type` (S3 or SFTP) in `pgbackrest.conf`. The
  overlay says so where it is configured, and [THREAT_MODEL.md](../THREAT_MODEL.md) lists it.
- **`restore-test` is the point.** It restores the latest backup into a throwaway container, replays the archived WAL and counts the
  links. A backup nobody has restored is a hope.
- **A streaming replica, promoted by hand.** A second service copies the primary with `pg_basebackup` through a replication slot, as
  `shortener_replicator`, a role that can only replicate. The slot is bounded (`max_slot_wal_keep_size=4GB`) so a replica that is gone
  cannot fill the primary's disk. It is for availability: **the application does not read from it**, so a link disabled on the primary
  is disabled for everyone at once ([0011](0011-in-process-redirect-cache.md)). It belongs on another node, by a label.
- **Nothing promotes it by itself.** Promotion is `pg_promote()` and a redeploy that points `SPRING_DATASOURCE_URL` and
  `SPRING_FLYWAY_URL` at the replica, in [DEPLOY.md](../DEPLOY.md#backups-and-a-replica). The roles and their privileges come across with the data.
- **Three alerts**: archiving that fails for five minutes, a replica that is not connected for five, and one more than 100 MiB behind
  for ten.
- **No partitioning.** It does not spread load over nodes: it divides one table inside one. At this size it fixes nothing, and it
  costs: the listing sorted by date ([0014](0014-offset-pagination-with-totals.md)) would merge the partitions, and the primary key would
  have to carry the partition key. Revisit it when there are hundreds of millions of rows, or when the indexes no longer fit in memory,
  and then with a hash of the short code, which keeps a redirect on one partition.

## Rehearsed

With Compose on one machine and Postgres 18.6, pgBackRest 2.59: the roles and the `pg_hba.conf` line made when the data directory is
first initialised; a full and a differential backup; `check`; a restore into a throwaway container that counted the 8,000 links of the
primary; a replica that streamed 5,000 inserts, refused a write, let the application role read; and, with the primary stopped, a
replica that promoted, accepted a write from `shortener_app` and still refused it a `DELETE`.

## Consequences

- Good: a lost table, a bad migration or a mistaken change can be undone to within five minutes, and a lost primary is minutes of work
  and not a rebuild.
- Cost: an image to build, two secrets and a volume, a cron entry that is easy to forget (nothing alerts on a missing backup yet), and
  a runbook for the promotion.
- Cost: pgBackRest's repository on the same node is not a backup against losing it. A replica on another node covers that, until the
  repository moves off.
- Cost: an application that is still pointed at the old primary after a promotion fails until the redeploy.
- Not done: Swarm with more than one node, the same repository on object storage, and an alert for a backup that did not run.
- A database that exists already needs two things by hand, because initialisation scripts run once: the role
  (`deploy/postgres/replication.sh`, then a configuration reload) and the first backup (`backup init`).

## Rejected

- WAL-G: the same job with fewer checks. pg_dump nightly: no point in time, and a day of links lost.
- Patroni: automatic failover, and an etcd to keep, which is the complexity [0016](0016-compose-and-swarm-instead-of-kubernetes.md) removed.
- A managed database: it makes the problem the provider's, and is the right answer for an instance that is meant to last. The stack
  already runs against one ([DEPLOY.md](../DEPLOY.md#managed-database)).
- Reading redirects from the replica: it lengthens the window in which a disabled link still redirects.
