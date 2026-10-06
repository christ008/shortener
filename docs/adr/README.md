# Architecture decision records

Why the system is the way it is, one decision per file. [INTERNALS.md](../INTERNALS.md) says how it works, and each ADR
says why that choice and not another, what it costs and what would make us undo it.

The decisions are recorded after the fact, from the history of the repository, and dated by the commit that made them.

## The problem

Everything below exists to serve this, and nothing else:

- A client gives the service a long URL and gets a short one. Anyone who follows the short one is redirected.
- A client sees and disables only its own links. An administrator can take down any.
- Following a link is the hot path and must stay fast and available.
- Anyone on the internet can follow a link, so anyone can also try to abuse the service.

The service is small: two tables' worth of data, one hot read and a handful of writes. A system this size can be buried
by what surrounds it, so each decision below must say what it pays for and what it costs.

## The decisions

Fit says how much of the complexity the decision adds, for a service of this size: **needed** for any deployment,
**needed when exposed** to strangers, or **earned** by a measurement.

| ADR | Decision | The problem it solves | What it costs | Fit |
|---|---|---|---|---|
| [0002](0002-plain-jdbc-on-postgres.md) | Plain JDBC, not JPA | atomic claim of a code needs `ON CONFLICT ... RETURNING` | hand-written SQL | needed |
| [0003](0003-modules-and-ports-checked-by-tests.md) | Public contract and adapters, held by tests | the web and storage layers drifting into the domain | two architecture test classes | needed |
| [0004](0004-absence-is-a-type.md) | Sealed types and null objects, not nulls | a missed case is a runtime error | more types | needed |
| [0005](0005-oauth2-resource-server-with-scopes.md) | OAuth2 resource server and scopes, no API keys | who may do what, without storing secrets | an identity provider to run | needed |
| [0006](0006-require-dpop-bound-tokens.md) | Tokens bound to the client's key (DPoP) | a leaked token is a usable token | clients must sign every request | needed when exposed |
| [0007](0007-owner-claim-and-ownership-rule.md) | Owner is the `owner` claim | one client, many people | identity provider mapper | needed |
| [0008](0008-rate-limits-per-instance-in-memory.md) | Token buckets in memory, per instance | token guessing, floods | limits multiply by replicas | needed |
| [0009](0009-problem-details-for-every-error.md) | One error shape, rendered by Spring MVC | clients parsing several formats | security failures go through an adapter | needed |
| [0010](0010-virtual-threads-and-bounded-connections.md) | Virtual threads, 500 connections | blocking JDBC, memory per connection | a ceiling that sheds load | earned |
| [0011](0011-in-process-redirect-cache.md) | Caffeine cache of active links | the pool of ten saturates before the CPU | takedowns take up to 30 s | earned |
| [0012](0012-readiness-excludes-the-database.md) | Ready without the database, serve known links | an outage restarting every instance | stale redirects for 5 minutes | earned |
| [0013](0013-three-database-roles-and-a-migration-job.md) | Three roles, migrations in their own job | an injection dropping the tables | a bootstrap script and a job | needed when exposed |
| [0014](0014-offset-pagination-with-totals.md) | Pages with totals, not cursors | a UI that jumps to any page | deep pages cost more | needed |
| [0015](0015-target-host-policy.md) | An allowlist of target hosts | an open redirector for phishing | optional, off by default | needed when exposed |
| [0016](0016-compose-and-swarm-instead-of-kubernetes.md) | Compose and Swarm, not Kubernetes | four operators for one service | no egress filter, no autoscaling | needed |
| [0017](0017-native-image-on-a-pinned-base.md) | Native image on pinned Alpaquita | startup, memory, attack surface | reflection hints, 3-minute builds | earned |
| [0018](0018-signed-scanned-releases.md) | Signed, scanned, smoke-tested release | trusting what runs in production | a workflow that has not run yet | needed when exposed |
| [0019](0019-no-secrets-in-git.md) | Generated dev keys, no secrets in Git | secrets in the history | a setup step per developer | needed |
| [0020](0020-dpop-client-in-java.md) | The DPoP client is one Java file | hand-made DER and JSON in a security client | a JDK 17 on the client's machine | needed |
| [0021](0021-encrypt-internal-traffic.md) | Both overlay networks encrypted | tokens crossing nodes in clear | IPsec cost, ports between nodes | needed when exposed |
| [0022](0022-mtls-between-services.md) | Mutual TLS between services (**proposed**) | an unauthenticated peer on a stack network | a CA and a rotation job | needed when exposed, not yet |
| [0023](0023-production-must-decide-its-targets.md) | Production refuses to start without a target decision | an open redirector by omission | one more variable to set | needed when exposed |
| [0024](0024-logging-and-audit.md) | Security events, audit trail and notices, each a line and a counter | an attack or a takedown that leaves no trace | local logs with client addresses | needed when exposed |
| [0025](0025-keycloak-in-the-stack.md) | Keycloak in the stack, optional, behind the edge | the provider decides ownership and admin, and none existed for production | one more service and an image to build | needed when exposed |
| [0026](0026-scripting-standard.md) | sh to start programs, Java to compute, nothing else (**the Java tier is superseded by 0029**) | five languages and a helper written twice | a JDK 17 to run a compute script | needed |
| [0027](0027-tools-on-jdk-25.md) | Tools on JDK 25, the client for strangers on 17 (**the tools are superseded by 0029**) | no shared code, a second per call, boilerplate | two baselines, by directory | needed |
| [0028](0028-postgres-backups-and-a-replica.md) | Backups with pgBackRest, a replica promoted by hand, no partitioning | losing the one disk that holds every link | an image, a cron entry, a promotion runbook | needed when exposed |
| [0029](0029-tools-in-kotlin.md) | The tools are Kotlin, in a build of their own | a second language, tests that start a process per case, a tool with no test | a build on the first run, and Gradle for `dev-setup` | earned |

0001 is the [format](0001-record-architecture-decisions.md).

## Reading it for proportion

- A private instance that only you use needs the **needed** rows. The rest is the cost of letting strangers in, or of
  proving a measurement, and is worth taking off the list if that is not the goal.
- The history already shows the correction: [0016](0016-compose-and-swarm-instead-of-kubernetes.md) removed four cluster
  operators that had never run on a cluster, and [0014](0014-offset-pagination-with-totals.md) reverted cursors that the
  product did not need.
- Security reasoning is in [THREAT_MODEL.md](../THREAT_MODEL.md). An ADR names the threat it answers, and the model
  names the ADR that mitigates it.

## Writing one

Copy the shape of [0001](0001-record-architecture-decisions.md). Number it next, keep it to one page, and never
rewrite an accepted one: add a new ADR that supersedes it and change the old status to `Superseded by NNNN`.
