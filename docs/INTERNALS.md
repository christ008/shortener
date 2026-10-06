# Internals

How the service works and why. The [README](../README.md) covers running it and [openapi.yaml](openapi.yaml) is the API
contract (a test keeps it in step with the code).

- [Structure](#structure)
- [Request flows](#request-flows)
- [Domain types](#domain-types)
- [Persistence](#persistence)
  - [Roles and migrations](#roles-and-migrations)
  - [Connections](#connections)
- [Concurrency](#concurrency)
- [Redirect cache](#redirect-cache)
- [Security](#security)
- [Errors](#errors)
- [Observability](#observability)
- [Native image](#native-image)
- [Deployment](#deployment)
- [Releasing](#releasing)
- [Performance](#performance)
- [Design review](#design-review)
- [Decisions](#decisions)
- [Limitations](#limitations)

## Structure

Two Spring Modulith modules: `shortlink` and `security`. In `shortlink` the public package is the contract and
everything else is an adapter behind it.

```
                 web (controller, DTOs)
                          │ calls
                          ▼
  ShortLinkService  ◄── authorization (method security, PermissionEvaluator, scopes)
  (interface)
        ▲ implemented by
  DefaultShortLinkService ──► ShortLinkRepository (interface) ◄── persistence (JdbcClient)
```

ArchUnit tests enforce what Modulith does not check inside a module:

- The public API depends on no JDBC type and no security type.
- The web adapter talks only to the service interface.
- Nothing depends on the web or persistence adapters.
- JDBC types never leave `persistence`.

## Request flows

**Create** (`POST /api/short-links`)

1. Rate limit by IP, authenticate (DPoP or bearer), rate limit by client.
2. The controller validates the body and calls `shorten` or `claim`.
3. Method security checks the scope (`create`, plus `claim` for a custom code) and that the owner argument is the caller.
4. The service validates the URL again as a domain rule, then allocates a code:
   - Generated: drawn with `SecureRandom`, inserted with `INSERT ... ON CONFLICT DO NOTHING`, retried up to 5 times,
     then `ShortCodeExhaustionException`.
   - Custom: one attempt, a conflict is `409`. `api`, `actuator` and `error` are reserved so they cannot shadow routes.
5. `201` with the link and a `Location` header built from the forwarded host and scheme.

**Redirect** (`GET /{code}`), public and the hot path

- Looks up the [redirect cache](#redirect-cache), then loads by primary key on a miss.
- `302`, `404` when unknown, `410` when disabled.

**Disable** (`DELETE /api/short-links/{code}`)

- Loads the link through `ManageableLinks`, whose `@PostAuthorize` lets only the owner or an administrator see it.
- Anyone else gets `404`, so a link's existence is not revealed.
- The update is `COALESCE`-based, so disabling twice keeps the first actor and time.

**List** (`GET /api/short-links`)

- Takes a Spring `Pageable` (`page`, `size`, `sort`), capped at 200 (`spring.data.web.pageable.*`).
- A client lists its own links. An administrator lists one client's or all.
- Returns a Spring `Page`: the items, whether another page follows, and the totals (`totalItems`, `totalPages`), so a
  client can offer any page, including the last.
  - `hasNext` comes from fetching `size + 1` rows.
  - The total is read from the page when it ends the listing (the offset plus the rows returned), and for an empty first
    page. Only a page with more behind it, or a page past the end, runs `SELECT count(*)`, filtered the same way.
  - The count is a separate statement, so a link created between the two can make them differ by one.
  - Counting scans the matching rows. Per client that is a small indexed range. For an administrator's unfiltered
    listing it is the whole table, which is fine at this size; revisit with an estimate if it grows large.
  - A page past the end is empty and carries the real totals, so a client can recover.
- Sortable by `createdAt` and `shortCode` only, from a whitelist that maps to columns, so a sort parameter never
  reaches SQL as text. Ties break on the short code, so pages never overlap.

## Domain types

Absence and "unknown" are types, not nulls, so callers match on them and the compiler checks every case.

| Type | Replaces | Cases |
|---|---|---|
| `LinkStatus` | nullable `disabledAt` and `disabledBy` | `Active`, `Disabled(at, by)` |
| `Actor` | nullable creator and disabler names | `Client(name)`, `Unknown` for links stored before creators were recorded |
| `CreatedByFilter` | nullable `createdBy` in listings | `Anyone`, `Only(client)` |
| `LinkLookup` | nullable `findByShortCode` and `disable` | `Found(link)`, `Missing` |
| `InsertResult` | nullable `insertIfAbsent` | `Created(link)`, `Taken` |
| `RedirectCache` | a nullable cache field | `CaffeineRedirectCache`, `NoRedirectCache` (does nothing) |
| `RateLimitDecision` | nullable wait | `Allowed`, `Limited(retryAfter)` |
| `RateLimitKey` | nullable key | `Of(value)`, `Unlimited` |
| `AuthScheme` | nullable scheme | `DPOP`, `BEARER`, `NONE` |

Nulls remain only where a framework owns the signature: JDBC columns, Caffeine's loader, the request DTO, Spring
Security callbacks. They are converted at that boundary, and the JSON API still sends `null` for an absent
`createdBy` or `disabledAt` because the contract says so.

## Persistence

Postgres 18 with plain JDBC through `JdbcClient`. Two statements dominate and both need SQL that JPA hides
(`ON CONFLICT`, `RETURNING`, collation).

- `short_link` has the short code as its natural primary key.
- Constraints keep bad data out whatever writes it: the code matches `^[A-Za-z0-9_-]{3,32}$`, the target starts with
  `http://` or `https://`, and `disabled_at` and `disabled_by` are both null or both set.
- Listing indexes: `(created_at DESC, short_code COLLATE "C" DESC)`, and the same behind `created_by`.
- `COLLATE "C"` matters: the stock Postgres image uses `en_US.utf8`, which orders `Z` and `a` differently from the
  bytes, so sorting by code would differ between databases.
- Migrations are Flyway `V1` to `V6`. The native image prints "Unable to scan location /db/migration". It is harmless:
  migrations apply from the registered resources.
- Storage that cannot serve the request now becomes `StorageUnavailableException`: `503` with `Retry-After: 5`, so it
  reads as retryable.
  - A failure to get a connection (`DataAccessResourceFailureException`).
  - A statement cut off at 5 s (`QueryTimeoutException`).
  - A lock given up after 2 s: an `UncategorizedSQLException` with SQLSTATE `55P03`, recognised by that code because
    Spring has no category for it.
  - Any other database error is not mapped, so a real bug is not hidden behind a retry. `DatabaseLimitsTest` makes both
    limits fire on the repository's own query.

### Roles and migrations

`deploy/postgres/bootstrap.sql` is idempotent and runs once per database, as a superuser, by whatever provisions it
(the dev compose file and the stack run it when the data directory is first created). It creates three roles:

- `shortener_migrator` owns the tables and runs Flyway.
- `shortener_app` serves requests.
  - Can `SELECT` and `INSERT` on `short_link`, `UPDATE` only `disabled_at` and `disabled_by`, and read the Flyway history.
  - Cannot `DELETE`, `TRUNCATE`, change a target or a code, or run DDL, so an injection or a compromised dependency
    cannot drop or rewrite links.
  - V6 states those grants next to the schema. Where the role does not exist, V6 does nothing, so a developer's database
    with one user works as before.
- `shortener_exporter` has `pg_monitor`: it reads statistics, not data.

Limits on the application role apply at login, so no application setting lifts them and they hold behind a pooler:

| Setting | Value | Why |
|---|---|---|
| `statement_timeout` | 5 s | a request needing more database time is a bug |
| `lock_timeout` | 2 s | a held lock must not pin one of an instance's ten connections |
| `idle_in_transaction_session_timeout` | 10 s | an abandoned transaction must not pin one either |

The migrator has `lock_timeout` 10 s and no statement timeout: a migration may run for minutes, but must fail rather
than queue behind a long query and block every later one. The migration job retries.

Migrations run in a one-shot job, the `migrate` service of the stack:

- It runs the same image with `SHORTENER_MIGRATE_ONLY=true` and the migrator's credentials. `MigrateOnlyRunner` ends the
  process once Flyway has run.
- Only that service gets the migrator's password secret (`ComposeStackTest` checks it), so code execution in the
  application does not yield the role that owns the tables.
- Flyway uses `SPRING_FLYWAY_*`, its own connection, so the pool's 15 s socket timeout cannot cut a long migration short.
- A failed job retries up to five times. `deploy/stack/deploy.sh` runs it before it updates the application and waits
  for it, so a failed migration leaves the old version serving.
- The previous version keeps serving against the new schema during a rollout, so migrations must be compatible with it:
  add, then switch, then remove in a later release.
- The application container still runs Flyway on start, as `shortener_app`, and finds nothing to apply. It cannot be
  switched off: a native image decides at build time which beans exist. If the schema is behind, the role cannot change
  it and the container fails to start (tested on an empty database: exit 1, `permission denied for schema public`).
- A database that predates the roles: run `bootstrap.sql`, which hands the existing tables to the migrator, then point
  the Secrets at the new roles.

`MigrationConventionsTest` enforces, from `V7` on, what two million rows showed:

- Adding a `CHECK` took an exclusive lock for 4.7 s and an ordinary `CREATE INDEX` blocked writes for 1.3 s, both
  growing with the table.
- Add a constraint `NOT VALID` and validate it in a later migration.
- Build an index `CONCURRENTLY`, in a migration with a `.conf` file saying `executeInTransaction=false`.
- Retyping, `NOT NULL`, dropping or renaming a column needs a note (`-- unsafe-ok: <reason>`).
- It reads text and is not a SQL parser, so it catches the usual mistakes, not every one.

### Connections

- The pool is named `shortener`. It keeps idle connections alive every two minutes, because a load balancer or NAT drops
  silent ones and the first request after that would fail.
- It logs a connection held for more than 10 s.
- Every connection reports `shortener-<hostname>` as its application name, so `pg_stat_activity` says who holds what.
- The driver waits at most 3 s to connect and 15 s for an answer, above the 5 s statement timeout, and keeps TCP alive.
  The answer timeout covers a network that goes silent, where the server cannot cancel anything.

## Concurrency

Requests run on virtual threads, so blocking JDBC does not hold platform threads. The database is bounded by the Hikari
pool (10 connections, 3 s wait).

- Throughput is capped by what 10 connections can serve, not by threads. The pool saturates first on the JVM, which is
  why redirects are cached.
- Each open connection holds about 150 KB of Tomcat buffers on the heap, so concurrency is bounded at the door:
  `server.tomcat.max-connections` (500) plus `accept-count` (100), and the rest are refused. That sheds load instead of
  exhausting memory. Keep connections times 150 KB well inside the heap.
- Shutdown is graceful: Spring stops accepting connections and waits up to 20 s for in-flight requests, inside the 30 s
  stop grace period. There is no `preStop` hook to lengthen it, so see [Deployment](#deployment) for how a rollout
  avoids sending traffic to a task that is stopping.

## Redirect cache

An in-process Caffeine cache per instance, in front of the database read that every redirect costs.

- **What.** Active links only. An unknown code is never cached, so a new link works at once. A disabled link is never
  cached, so a takedown is not extended. Only `resolve` uses it. `get`, `list` and `disable` read the repository, so
  the checks that decide who may see or change a link never see a stale one.
- **How long.** `shortener.shortlink.redirect-cache.ttl`, 30 s by default. The instance that disables a link evicts it at
  once. The others serve it until their entry expires, so a takedown reaches every instance within the TTL.
- **How many.** `max-entries`, 100,000 by default, evicted by Caffeine's admission policy. A link is a few hundred
  bytes. `enabled: false` swaps in `NoRedirectCache`.
- **Misses.** Concurrent misses for one code run one load and share it. A miss for an unknown or disabled code does a
  second lookup to tell them apart, because only active links are stored.
- **Metrics.** `cache_gets_total`, `cache_size`, `cache_evictions_total` for the cache `shortlink.redirect`.
- **Outages (`stale-if-error`, 5 minutes, 0 turns it off).** When the database cannot be reached and an entry has
  expired, the cache serves the last copy this instance read.
  - A code the instance never read still gets the `503`, and so does any failure that is not the database being
    unreachable.
  - The copies live in a second, equally bounded cache used only on that failure, so the hit ratio counts only what
    memory answered. Serving stale is not a hit.
  - A link the database then reports gone or disabled is dropped, as is one disabled on this instance.
  - Cost: a link disabled elsewhere just before an outage keeps redirecting here until its copy expires. That is why the
    window is short. It cannot be shorter than the TTL.
  - Each redirect served this way increments `shortlink_redirect_cache_stale_total`, which should be zero.
  - Drilled on the native binary with Postgres stopped: a link read earlier kept redirecting, an unread code got `503`
    with `Retry-After`, readiness and liveness stayed up, and the instance recovered when Postgres returned. With the
    window at zero the same link answered `503`.

Rejected:

- Redis or a shared cache: an operational dependency and a network hop for a problem one process solves.
- Cross-instance invalidation with `LISTEN/NOTIFY`: more machinery and a new failure mode for bounded staleness.
- Caching 404s or 410s: delays new links or takedowns.

## Security

### Filter chain

Stateless and deny by default, in this order: IP rate limit, authentication, client rate limit, authorization.

- `/api/**` needs authentication.
- `GET` and `HEAD` on `/{code}` and the health and Prometheus endpoints are public.
- Everything else is denied.
- Responses carry a restrictive CSP, `Referrer-Policy: no-referrer` and Spring Security's default headers.

### Tokens

- JWTs from Keycloak, validated locally against its JWKS: signature, issuer, audience (`shortener-api`), expiry and the
  JOSE type `at+jwt` (RFC 9068). No call to the identity provider per request.
- Issuer-agnostic: any provider that issues these tokens works. Keycloak is the reference.
- The claim named by `shortener.security.client-id-claim` (`owner` in the shipped config, `azp` by default) is the client id and is stored as the creator of every link.
- Only the `Authorization` header carries a token. A token in a query string or form body is ignored (tested).

### DPoP (RFC 9449)

Every access token must be bound to the client's key:

1. The client authenticates to the token endpoint with a signed assertion (`private_key_jwt`, ES256) and a DPoP proof.
   It receives a token whose `cnf.jkt` is the thumbprint of its DPoP key.
2. Each call sends `Authorization: DPoP <token>` and a `DPoP` proof for that exact request: method (`htm`), URL without
   query (`htu`), token hash (`ath`), a unique `jti` and the time.
3. Spring Security checks the signature, that the key matches `cnf.jkt`, method, URL, token hash, age and that the
   `jti` is new.

Properties:

- The DPoP key is not the client's identity. The client is authenticated by its registered assertion key. The DPoP key
  is generated by the client, bound to one token and can be rotated freely.
- A stolen token is useless without the private key, and the `Bearer` scheme is refused even for a valid token.
- Behind a gateway, `htu` is built from the forwarded host and scheme, so forwarded headers are handled by Tomcat's
  trust-aware valve.
- The replay cache is in memory and per instance. A proof is remembered 30 s, at most 1,000 per DPoP key, about 30
  requests a second per key. A client can use several keys, so this is not a client limit.
- DPoP nonces are not supported.
- `shortener.security.dpop.required=false` also accepts plain bearer tokens (development and tests).
- `/.well-known/oauth-protected-resource` (RFC 9728) tells clients the authorization server and that DPoP is required.

### Authorization

Scopes decide what a client may do, and their names are configuration. Checks are declared on the service with method
security meta-annotations (`@MayCreate`, `@MayClaim`, `@MayRead`, `@MayList`, `@MayDisable`).

| Scope | Allows |
|---|---|
| `shortlinks:create` | create with a generated code |
| `shortlinks:claim` | also choose the code (needs `create` too) |
| `shortlinks:read`, `shortlinks:delete` | read and disable the client's own links |
| `shortlinks:admin` | read and disable any client's links |

- Ownership is a rule on top of scopes: the `createdBy` and `disabledBy` arguments must equal the caller, and a listing
  filter must be limited to the caller (`#filter.isLimitedTo(authentication.name)`).
- A `PermissionEvaluator` lets a caller manage a link only if it created it, or is an administrator.
- A denied link read becomes `404` through `@HandleAuthorizationDenied`.

### Rate limiting

- Token buckets in memory (Bucket4j over a size-bounded Caffeine cache): 300 requests a minute per IP before
  authentication, 60 a minute per client after it.
- State is per instance, so the effective limit is the limit times the replicas. A global limit needs a shared store.
- Actuator paths are never limited, so health checks cannot be starved.

### Identity provider and OAuth 2.1

`deploy/keycloak/shortener-realm.json` defines the scopes, the audience mapper and four clients (`demo`, `other`,
`admin`, `no-scope`): confidential, service-account only, implicit and password grants off. Against the OAuth 2.1 draft
that covers tokens only in the header, no deprecated grants, five-minute tokens, sender-constrained tokens and
asymmetric client authentication. The draft is not final, so this is alignment, not conformance.

## Errors

Every error is an RFC 9457 problem detail (`application/problem+json`), rendered by Spring MVC.

- **Domain failures** extend `ShortLinkException`, a thin subclass of Spring's `ErrorResponseException`. Each carries its
  status and message, and `StorageUnavailableException` adds `Retry-After`. There is no `@ControllerAdvice` of our own:
  Spring's `spring.mvc.problemdetails.enabled` advice renders them.
- **Security failures** (401, 403, 429) happen in the filter chain, before MVC. `SecurityProblemResponder` builds a
  `SecurityProblem` (also an `ErrorResponseException`, with `WWW-Authenticate` or `Retry-After`) and hands it to MVC's
  exception resolver, so the body has the same shape as every other error and follows the client's `Accept`.
- A `401` challenge names `DPoP` with the accepted algorithms, and `Bearer` as well unless DPoP is required. The error
  is `invalid_token` (bad token or wrong scheme), `invalid_request` (missing proof) or `invalid_dpop_proof`.

## Observability

What is reported, the dashboard, the traces and the alerts are in [OBSERVABILITY.md](OBSERVABILITY.md). The decisions:

- **Readiness excludes the database.** Every instance shares one, so removing them all from rotation gains nothing, and
  the health check that restarts unhealthy containers would restart all of them during an outage. An instance answers
  what it can and gives `503` with `Retry-After` for the rest. A rollout stays safe because a new instance only starts
  after the migration job has reached the database.
- **The trace exporter is always built in.** A native image decides at build time which beans exist, so an endpoint that
  is missing when the image is built, or given only at run time, compiles the exporter out silently. The endpoint has a
  default in `application.yaml`, and environment variables still override it.
- **Metrics are labelled by route template**, so short codes never become label values.
- **The management port is internal.** Health and metrics are never proxied by the edge.
- **The database explains itself, without the application:** slow-statement logging, an exporter that cannot read data,
  and a diagnostics script. See `deploy/postgres/diagnostics.conf` and `perf/pg-diagnostics.sql`.

## Native image

Built by `./gradlew bootBuildImage` on BellSoft's Alpaquita (musl) builder with Liberica NIK, through Paketo
buildpacks.

- **Pinned.** Paketo buildpacks are pinned by version in `build.gradle.kts`, in a list that replaces the builder's
  default order. BellSoft publishes only rolling `musl` and `glibc` tags for its builder and run image, so those two
  are pinned by digest. Update by bumping a version, or looking up a new digest.
- **Health check.** The `health-checker` buildpack adds Tiny Health Checker at `/workspace/health-check`, a static
  binary that works on any stack and does not touch the native image. Run it with `THC_PORT=8081` and
  `THC_PATH=/actuator/health/readiness`. Set the Docker health timeout above the 3 s database check.
- **Runtime.** Starts in about 0.35 s, around 100 MB at rest. Runs as uid 1000. `-march=compatibility` runs on any
  x86-64 host. `-J-Xmx7g` for the build, which needs about 7 GB free (the OOM killer ends it with exit 137).
- **Hardening tested.** Healthy under `--read-only`, `--cap-drop ALL` and `no-new-privileges`. The run image still has a
  shell (busybox).

### Size

The binary is the image: about 157 MB of a roughly 205 MB image (75 MB compressed).

| Part | Size |
|---|---|
| code area | 76 MB |
| image heap | 80 MB |
| run image | about 35 MB |

- `-Os` (optimize for size) is on. It shrinks the binary to 125 MB (code area 48 MB).
- Its cost, measured with `perf/bench.sh` at 1,500 and 5,000 req/s, two runs each, ordered baseline, `-Os`, `-Os`,
  baseline: same throughput, no failures, redirect p99 of 1.0 ms at 5,000 req/s in all four runs. It uses about 10% more
  CPU for the same load (48% against 53% of the two cores at 5,000 req/s). Capacity beyond 5,000 req/s was not
  measured, so expect about 10% less headroom. Results: `perf/results/2026-10-05-os`.
- Dependencies are not the lever. Moving Spring Modulith to test scope saved 0.3 MB.
- Largest contributors: `java.base` 17 MB, Tomcat 6 MB, kotlin-reflect 5 MB, 13,000 types registered for reflection.
- UPX shrinks it further but costs startup time and memory sharing, which is the point of a native image.

### What the native image had to be taught

| Failure | Cause | Fix |
|---|---|---|
| Rate limiter cannot build its cache | Caffeine picks generated classes by name | `CaffeineRuntimeHints` registers them |
| `management.server.port` ignored | read at AOT time | set it in `application.yaml`, not the environment |
| Tomcat missing a reflection entry | `server.tomcat.mbeanregistry.enabled=true` | removed |
| Every DPoP request is `401` with no reason | the DPoP filter is added only if `ClassUtils.isPresent(...)` finds a class | `DpopRuntimeHints`, plus a startup check that fails if DPoP is required and the filter is missing |
| Authorized calls fail with `500` | SpEL reads `authentication.name` and `#filter.isLimitedTo(...)` by reflection | `AuthorizationRuntimeHints` registers them |

Unit tests cannot run a native image, so `perf/smoke.sh` exercises every endpoint with real tokens (21 checks).
`./gradlew bootBuildImage -PnativeProfiling` adds JFR and heap dumps (`shortener:<version>-profiling`).

## Deployment

One file, `compose.prod.yaml`, runs as `docker stack deploy` on Swarm and as `docker compose` on one host. The runbook is
[DEPLOY.md](DEPLOY.md). Kubernetes was dropped: four cluster operators, none of which ever ran on a real cluster, for a
service this size.

Services:

- `edge`: nginx, unprivileged. TLS, redirect to HTTPS, limits. The only service that publishes ports.
- `shortener`: the application, two tasks, each with its own DNS address (`dnsrr`).
- `migrate`: the one-shot migration job.
- `postgres`: the database, on one node with a local volume. A managed database replaces it by dropping the service.
- Overlay `compose.prod.observability.yaml`: Prometheus with the alert rules, and the Postgres exporter.

### Hardening

`ComposeStackTest` holds every service to this.

| Control | How | Swarm |
|---|---|---|
| Read-only root filesystem | `read_only`, writable memory mounted for `/tmp` | applied |
| No capabilities | `cap_drop: [ALL]`; only Postgres adds the five its entrypoint needs | applied |
| Not root | `user` 1000, 101 and 65534; Postgres starts as root and hands over | applied |
| No privilege gain | `no-new-privileges` | **ignored**, see below |
| Writable memory | long `volumes:` syntax with a size | applied. The short `tmpfs:` key is silently dropped |
| Resources | memory and CPU limits, rotated logs | applied |
| Secrets | Docker secrets, read as files by Spring's `configtree:` | applied: in memory at `/run/secrets` |
| Network | `data` has no route out, and encrypts across nodes on Swarm | applied |
| Exposure | only the edge publishes, in host mode so it sees client addresses | applied |
| Health | Tiny Health Checker on the readiness probe; Swarm replaces unhealthy tasks | applied |

- `no-new-privileges` is not applied by Swarm (verified on Docker 29.8). The application, nginx and Prometheus images
  have no setuid binaries, so it would add nothing. Postgres has some, and with every capability dropped they have
  nothing to escalate to.
- The app and migration job get the database password as the file `spring.datasource.password`, named like the
  property. Only the migration job gets `spring.flyway.password`.

### Edge

- Certificates are files (`tls_cert`, `tls_key` secrets). Issuing and renewing them is outside the stack. nginx has an
  ACME module (HTTP-01 and TLS-ALPN-01, no wildcards), which is the way to automate it. It was not tried.
- Replaces the forwarded headers rather than appending, because the application builds the DPoP proof's URL from them
  and trusts them only from private addresses.
- Caps bodies at 16 KiB, sets header, body and proxy timeouts, and caps connections per address. Never proxies the
  management port.
- Resolves `shortener` every 5 s, so tasks that come and go are followed without a reload.

### Rollouts

- The application updates one task at a time, new before old (`start-first`), waits for the health check, watches the
  new task for 20 s and rolls back if it fails.
- A stopping task may still be in nginx's DNS answer for a few seconds. nginx retries a request that failed to connect
  on the next address, for requests that are safe to repeat.
- Measured: 1,290 redirects at 20 requests a second during a forced rolling update of two tasks, every one answered.
  Creates were not measured: nginx does not repeat a request that was already sent.
- `deploy.sh` runs the migration job first, then the application, so a migration never meets a task it was not
  written for.

### What this gives up against Kubernetes

- No egress filtering by destination. The `data` network has no route out, and the `edge` network, which the application
  needs to reach the identity provider, has all of it.
- No autoscaling. Replicas are set by hand.
- No `preStop` hook, so the guarantee is health gating plus short DNS caching plus a retry for repeatable requests.
- `no-new-privileges` is not applied.
- Secrets and configs are immutable: rotating one means a new name and a stack update.
- The database is one instance on one node, with no replication and no backups unless you add them.
- Prometheus cannot be published to the loopback address only, so it is not published at all.

### Verified

On Docker 29.8, a single-node swarm and plain Compose, with the native image:

- the whole stack through the TLS edge, with the 21-check smoke test (`perf/smoke.sh`), on both;
- the migration job as the migrator role from a file secret, and the application refusing to change the schema;
- Prometheus discovering both tasks and loading the five alert rules, and the exporter reporting `pg_up`;
- a rolling update under load.

Not verified: more than one node (overlay encryption, host-mode edge on several nodes, where Postgres lands), real
certificates and ACME, pulling from a registry, and any failure of the database node.

## Releasing

Change `version` in `build.gradle.kts` (the OpenAPI document and the deployment files must match, a test checks), commit,
tag the commit `v<version>` and push the tag. The `Release` workflow (`.github/workflows/release.yml`):

- refuses a tag that does not match the version in the build;
- runs the tests, builds the native image, and smoke-tests that exact image against Postgres and Keycloak;
- scans it, and fails on a fixable high or critical vulnerability;
- pushes that image to `ghcr.io/christ008/shortener`, signs it by digest with the workflow's own identity (no key to
  manage), and attaches an SPDX bill of materials. The job summary prints the digest and the `cosign verify` command.

Notes:
- It has not run yet. Only amd64 is built.
- The package is private after the first release. Make it public in the repository's package settings, or give the
  hosts that pull it a registry login.
- Dependabot proposes updates to the actions weekly. Pin them to commit hashes once the workflow is stable.

## Performance

### Method

- `perf/bench.sh` runs one build in a container with 2 CPUs (cores 0-1) and 512 MB. Postgres uses cores 2-5 and k6 cores
  6-9.
- k6 uses an arrival-rate executor, so slow responses do not slow the load: 99% redirects on a hot subset, 1% creates
  signed with DPoP.
- Each variant warms up 30 s, then runs 1,500, 5,000 and 10,000 requests a second. Server-side percentiles come from
  Prometheus. `perf/run-all.sh` runs the variants.
- One laptop, one run per rate, database and load generator on the same machine. Treat the numbers as shape, not capacity.

### Results

| | JVM (Temurin 25) | native |
|---|---|---|
| ready after `docker run` | 5.4 s | 0.7 s |
| memory at rest | 250 MiB | 210 MiB |
| 1,500 req/s, redirect p99 | 1.0 ms | 4.6 ms |
| 5,000 req/s served | 4,942 | 3,264 (1.6% failed) |
| 5,000 req/s, redirect p99 | 2.4 ms | 3,018 ms |
| 10,000 req/s | overload: 713 served | overload: 318 served |

These predate the [redirect cache](#redirect-cache). With it:

- JVM: redirect p99 of 1.0 ms at 1,500 and 5,000 req/s, no failures, 32% of the two cores at 5,000. The ceiling was not
  probed. Only those two rates were run, because saturation runs can take the development machine's network down.
- Native: 4,936 req/s at 5,000, no failures, p99 1.0 ms, peak 142 MiB, where it failed before.
- Results: `perf/results/2026-10-03-cache`.

### Native image under overload

The native image lost its heap at 5,000 requests a second. Profiled with JFR, GC logging and a heap dump:

- **No leak.** Live heap stayed at 27-33 MB over four minutes at 1,500 req/s, with 4.6% of time in GC.
- **Retention follows connections.** The dump at the `OutOfMemoryError` held 3,000 Tomcat requests, one per k6 client.
  About 450 of 453 MB was Tomcat's per-connection buffers, roughly 150 KB each.
- **A feedback loop.** A slightly slower server makes an open-model client open more connections, each costs heap, GC
  pauses grow (up to 2.8 s), and it slows further until the heap is full. The JVM stays below that point.
- **Collector.** Serial GC, young generation 10% of the heap. A 30% young generation did not help.
- **Allocation.** 12.7% of sampled allocation is virtual-thread stack copies. Micrometer observation, including one per
  Spring Security filter, is another visible share.

Two changes closed it:

- **The redirect cache** removes the read that made the native image slow, so clients do not pile up. Connection limits
  of 8192, 1000, 500 and 250 gave the same results at 5,000 req/s.
- **The connection limit** matters beyond capacity. At 12,000 req/s, about 2.4 times what two cores sustain:

  | | no limit (8192) | `max-connections: 500` |
  |---|---|---|
  | peak memory | 514 MiB (the limit) | 208 MiB |
  | time in GC, longest pause | 14.3%, 2.6 s | 3.9%, 60 ms |
  | `OutOfMemoryError` | 3 | 0 |
  | redirect p99 (server) | 4,144 ms | 1.0 ms |
  | served, failed | 5,837 a second, 0.08% | 4,605 a second, 1.08% |

  It trades about 1% failed requests for flat memory and latency. Creates still queue for seconds at that load, so a
  limit is protection, not capacity.

Not yet tested: turning off Spring Security observations. Tooling: `perf/profile.sh`, `perf/gc-summary.py`,
`perf/hprof-histogram.py`, `perf/tune-connections.sh`.

### Running load tests safely

The development machine runs an application firewall (Safing Portmaster) that inspects new connections through a kernel
packet queue. Overload runs overflow it (`nfnetlink_queue: nf_queue: full ... dropping packets`) and every new
connection fails until reboot. This took the network down three times.

- Keep to sustainable rates.
- Prefer host networking and loopback to the Docker bridge.
- Pause the firewall before saturation tests.
- When connections start timing out, check `journalctl -k | grep nf_queue`.

## Design review

A pass for what the compiler can check, and against SOLID and Tell, Don't Ask. What changed, and what was left alone on
purpose.

### Checked by the compiler

| Where | Now | Gives |
|---|---|---|
| `ShortLinkException` | `sealed`: the failures are exactly the subclasses in the package | no failure the API contract does not describe can be added from outside; a test lists them |
| `LinkStatus`, `Actor`, `LinkLookup`, `InsertResult`, `CreatedByFilter`, `RateLimitDecision`, `RateLimitKey` | sealed interfaces | a `when` over them needs every case |
| `ShortLinkResponse`, `CaffeineRedirectCache` | exhaustive `when` where `as?` casts stood | a new case is a compile error, not a silent `null` |
| `SecurityProblemResponder` challenge | `when` over `AuthScheme`, not over booleans | each scheme's answer is stated, and a new scheme must be handled |
| `ShortLinkScopes` | immutable, bound through the constructor | the authorization rules cannot change after start |

Left as they are:

- **Kotlin `internal`.** It means module-wide, and this is one module, so it would hide nothing. The boundaries are held
  by Spring Modulith and the ArchUnit tests instead.
- **Value classes** for `ShortCode`. Spring MVC, Jackson and native image support for them is thinner than for data
  classes, and the gain is one allocation.
- **Unchecked cast in `NotFoundWhenDenied`.** The first argument of a denied call is a framework-supplied `Object`.

### Tell, don't ask

Callers were pulling fields out of an object to decide something that the object knows. Each now asks the object to do
it, which puts the rule in one place and lets the type change without its callers.

| Before | Now |
|---|---|
| the evaluator compared `link.createdBy` with the caller | `link.isCreatedBy(client)` |
| the service read `link.isDisabled` and threw | `link.requireActive()` |
| the service checked `shortCode.value in RESERVED_CODES` | `shortCode.requireClaimable()` |
| the controller chose the listing filter from the caller's authority | `CreatedByFilter.of(requested, caller, isAdministrator)`, with tests |
| the service parsed the target and would have had to know which hosts are allowed | a `TargetUrlPolicy` says whether a target is accepted |

### SOLID

| Principle | Finding | Decision |
|---|---|---|
| Single responsibility | `DefaultShortLinkService` also opens the observations around two repository calls | kept: the `shortlink.load` observation marks a redirect that missed the cache, and a repository decorator would also observe the reads that decide who may see a link |
| Open/closed | which targets are accepted was going to be an `if` in the service | `TargetUrlPolicy`: `AnyTarget` and `AllowedHosts`. A new rule is a new implementation |
| Liskov | the two null objects, `NoRedirectCache` and `AnyTarget`, must honor their contracts | each has tests for its contract |
| Interface segregation | the repository has four operations, each used by the service or `ManageableLinks` | nothing to split |
| Dependency inversion | the service depends on interfaces for the repository, the cache, the generator and the policy. Its public contract exposes Spring Data's `Page` and `Pageable` | kept. The ArchUnit test keeps JDBC and security types out of the contract and accepts Spring Data's paging types. Own paging types would be a copy of them |

## Decisions

- **Plain JDBC, not JPA.** Two statements dominate and need SQL features JPA hides. Only the persistence adapter knows SQL.
- **Spring facilities over bespoke code.** Method security with a `PermissionEvaluator`, `Pageable` and `Page`, Spring
  Security's resource server, DPoP and RFC 9728 metadata, MVC's problem details. Custom code is limited to what Spring
  lacks: the Bearer refusal, rate limiting and the native hints.
- **Absence is a type.** Sealed types and a null-object cache instead of nullable returns and fields (see
  [Domain types](#domain-types)).
- **Virtual threads, not coroutines.** Each request does one blocking query, so there is nothing to fan out. The native
  image's problem was never scheduling: it was 150 KB of Tomcat buffers per connection and the collector. Revisit if a
  request ever calls several things in parallel.
- **Ownership is the client id.** There are no end users yet, so the token's `azp` is the owner and scopes are the only
  permission model.
- **Sender-constrained tokens by default.** A leaked bearer token is the main risk of a token API. DPoP removes it at the
  cost of a client that can sign. A JDK-only client is provided.
- **In-process redirect cache.** Caffeine, active links only, short TTL, local eviction on disable. See
  [Redirect cache](#redirect-cache) for what was rejected.
- **Which hosts a link may point to is a policy.** Empty by default, so a private instance accepts anything; a public
  one lists the hosts it accepts, so it cannot be used to redirect to arbitrary sites.
- **Reproducible image.** Everything the build downloads is pinned, by version where one exists and by digest where
  not.
- **Offset pagination with totals, not cursors.** Listings take `page` and `size` and answer with totals, so a client can
  jump to any page and draw a numbered pager. The web UI needs that. Cursor (keyset) paging only moves to the next or
  previous page. Its advantages (constant cost at any depth, stable pages under inserts) do not matter at this size. See
  [Request flows](#request-flows).

## Limitations

- A link disabled on one instance can redirect on the others for up to the cache TTL (30 s).
- Rate limits and the DPoP replay cache are per instance.
- Beyond capacity (about 5,800 req/s on two cores) the service sheds load, but creates still queue for seconds. nginx
  caps connections per address but not in total, which Tomcat does at 500.
- The stack has run on one node only, see [Deployment](#deployment).
- The database connection is not forced to use TLS: the URL comes from the environment and the driver falls back to plain
  text. Production should use `sslmode=verify-full`.
- No proxy metrics, no alerts on the database server itself, and nothing sends the alerts that Prometheus fires.
- The dev keys and secrets are committed deliberately and are public.
