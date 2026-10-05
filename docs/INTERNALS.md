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
- [Kubernetes](#kubernetes)
- [Performance](#performance)
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
(compose and the local overlay run it when the data directory is first created). It creates three roles:

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
| `lock_timeout` | 2 s | a held lock must not pin one of a pod's ten connections |
| `idle_in_transaction_session_timeout` | 10 s | an abandoned transaction must not pin one either |

The migrator has `lock_timeout` 10 s and no statement timeout: a migration may run for minutes, but must fail rather
than queue behind a long query and block every later one. The init container retries.

Migrations run in an init container:

- It runs the same image with `SHORTENER_MIGRATE_ONLY=true` and the migrator's credentials. `MigrateOnlyRunner` ends the
  process once Flyway has run.
- Only that container gets the `shortener-db-migrator` Secret (`MigrationCredentialsTest` checks it), so code execution
  in the application does not yield the role that owns the tables.
- Flyway uses `SPRING_FLYWAY_*`, its own connection, so the pool's 15 s socket timeout cannot cut a long migration short.
- Init containers have no startup probe, so a long migration is not killed, and a failed one leaves the rollout stopped
  with the old pods still serving.
- Every new pod starts it, HPA scale-ups included. After the first it is a no-op (Flyway's advisory lock makes
  concurrent ones safe) and costs about as much as the application takes to start: 0.5 s native, 7 s on the JVM.
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
- Every connection reports `shortener-<pod>` as its application name, so `pg_stat_activity` says who holds what.
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
- Shutdown is graceful: a 5 s `preStop` sleep lets the endpoint leave the Service, then Spring waits up to 20 s for
  in-flight requests, inside the 30 s termination grace period.

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
- Actuator paths are never limited, so probes cannot be starved.

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

- **Metrics**: `/actuator/prometheus` on management port 8081, which is internal.
  - Key series: `http_server_requests_seconds`, a histogram tagged by route template and status, so short codes never
    become label values (tested).
  - Hikari, JVM and process metrics come with it.
- **Dashboard**: `deploy/observability/grafana/dashboards/shortener.json`, twelve panels.
  - A panel stays empty until traffic arrives.
  - The native image has no GC beans, so its GC panel is empty and its heap maximum reads zero.
- **Logs**: structured ECS JSON under the `production` profile.
- **Health**: liveness and readiness on the management port. Readiness is the application's own state and does not
  include the database. Every instance shares one database, so removing them all from rotation gains nothing, and the
  health check that restarts unhealthy containers would restart all of them during an outage. An instance answers what
  it can and gives `503` with `Retry-After` for the rest. A rollout stays safe because a new instance only starts after
  the migration job has reached the database.
- **Traces**: Micrometer Tracing with the OpenTelemetry bridge, exported over OTLP/HTTP.
  - A server span per request, named by route, joined to the caller's trace through `traceparent`.
  - `shortlink.load` marks a redirect that missed the cache, `shortlink.insert` each attempt to store a link. Both are
    also timers.
  - Trace and span ids are in the logs. Spring Security's per-filter observations are off to avoid a span per filter.
  - Sampling: 0 by default, 100% under `dev`, 5% under `production`.
  - The exporter is always built in and the endpoint has a default in `application.yaml`: a native image decides at
    build time which beans exist, so removing the endpoint or supplying it only at run time compiles the exporter out
    silently. Environment variables still override it at run time.
  - OTLP export of logs and metrics is off.

- **The database**, none of it needing the application:
  - `deploy/postgres/diagnostics.conf` makes the server explain itself: `pg_stat_statements`, `track_io_timing`,
    statements slower than 250 ms logged with their plan (`auto_explain`) and the user, database and application name,
    lock waits, sorts that spill to disk and slow autovacuums. Compose and the local overlay load it. A managed
    database takes the same settings as parameters.
  - The compose `observability` profile runs `postgres_exporter` as `shortener_exporter`, limited to 25 statements and
    no query text. Prometheus scrapes it.
  - `perf/pg-diagnostics.sql` is what to run when it is slow: connections by application and state, what waits for a
    lock, the costliest statements, cache hit ratio, dead rows, unused indexes and distance from transaction ID
    wraparound. It works as a member of `pg_monitor`.
- **Alerts**: `overlays/production/prometheusrule.yaml` has four, on what the application sees of its database.
  - Requests waiting for a connection, connection timeouts, a slow wait for a connection, and more than 1% of requests
    answered 503.
  - Each has a unit test with simulated series (`prometheusrule.test.yaml`, run with `promtool`, not part of CI).
  - The server's own alerts (connections against `max_connections`, replication, backup age, transaction age) depend on
    how Postgres is run, and are not here.

Not done: Envoy metrics and spans, and a Grafana dashboard for Postgres (the exporter's series are there to build one).

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

## Kubernetes

`deploy/k8s`:

- `base`: Deployment (two replicas, rolling update without unavailability, topology spread, non-root, read-only root
  filesystem, no capabilities, probes on the management port, an init container that applies the migrations with its
  own credentials), Service, ServiceAccount, PodDisruptionBudget.
- `gateway`: Envoy Gateway resources for both overlays.
  - `EnvoyProxy`: two replicas, a disruption budget, source address preserved.
  - `ClientTrafficPolicy`: header and idle timeouts.
  - `BackendTrafficPolicy`: bodies capped at 16 KiB, 15 s request timeout.
  - `gatewayclass` is cluster-wide, applied once.
- `overlays/local`: kind with in-cluster Postgres and Keycloak, the proxy on `localhost:8088`, `/realms` routed to
  Keycloak. Postgres is initialised with `deploy/postgres`, so it has the same three roles as production, each with its
  own Secret.
- `overlays/production`: restricted pod security, cert-manager TLS with HTTP to HTTPS redirect, an HPA (2 to 10 on CPU),
  a NetworkPolicy (gateway to 8080, monitoring to 8081), two ExternalSecrets (the application's and the
  migrator's), a PodMonitor.

Assumes Envoy Gateway, cert-manager with Gateway API support, External Secrets and the Prometheus operator. Egress is
open on 5432 and 443 because the database and identity provider addresses are environment specific.

Tested on kind with stand-in backends: routing, forwarded address handling, the body cap, the request timeout. Not
tested: cert-manager issuance, a real Keycloak behind the gateway, the PodMonitor.

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
- **Reproducible image.** Everything the build downloads is pinned, by version where one exists and by digest where
  not.
- **Offset pagination with totals, not cursors.** Listings take `page` and `size` and answer with totals, so a client can
  jump to any page and draw a numbered pager. The web UI needs that. Cursor (keyset) paging only moves to the next or
  previous page. Its advantages (constant cost at any depth, stable pages under inserts) do not matter at this size. See
  [Request flows](#request-flows).

## Limitations

- A link disabled on one instance can redirect on the others for up to the cache TTL (30 s).
- Rate limits and the DPoP replay cache are per instance.
- Beyond capacity (about 5,800 req/s on two cores) the service sheds load, but creates still queue for seconds. The
  proxies do not yet limit connections to match Tomcat.
- The Envoy Gateway, cert-manager and Prometheus operator parts are untested on a real cluster, and so are the migration
  init container and the local overlay's role setup. The manifests render, and the migrate-only run, the role
  privileges and Flyway as the application role ran against Postgres 18, on the JVM and as a native binary built with
  GraalVM 25, but not in the Paketo image.
- The database connection is not forced to use TLS: the URL comes from a Secret and the driver falls back to plain
  text. Production should use `sslmode=verify-full`.
- No Envoy metrics or spans, no alerts on the database server itself, and the alert rules are not tested in CI.
- The dev keys and secrets are committed deliberately and are public.
