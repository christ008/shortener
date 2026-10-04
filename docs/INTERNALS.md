# Internals

How the service works and why it is built this way. The [README](../README.md) covers running it, [openapi.yaml](openapi.yaml) is the API contract (a test keeps it in
step with the code), and this covers what is inside.

- [Structure](#structure)
- [Request flows](#request-flows)
- [Persistence](#persistence)
- [Concurrency](#concurrency)
- [Redirect cache](#redirect-cache)
- [Security](#security)
- [Observability](#observability)
- [Native image](#native-image)
- [Kubernetes](#kubernetes)
- [Performance](#performance)
- [Decisions](#decisions)
- [Limitations](#limitations)

## Structure

Two Spring Modulith modules, `shortlink` and `security`. Inside `shortlink`, the public package is the contract and
everything else is an adapter behind it:

```
                 web (controller, DTOs)
                          │ calls
                          ▼
  ShortLinkService  ◄── authorization (method security, PermissionEvaluator, scopes)
  (interface)
        ▲ implemented by
  DefaultShortLinkService ──► ShortLinkRepository (interface) ◄── persistence (JdbcClient)
```

The rules are enforced by ArchUnit tests, because Modulith does not check layering inside a module: the public API
depends on no JDBC type and no security type, the web adapter talks only to the service interface, and nothing
depends on the web or persistence adapters. The service interface returns domain types and Spring Data `Slice`; the
JDBC types never leave `persistence`.

## Request flows

**Create** (`POST /api/short-links`)

1. Rate limit by IP, then bearer or DPoP authentication, then rate limit by client.
2. The controller validates the body (`targetUrl` must be an http or https URL, `customCode` must match the code
   pattern) and calls `shorten` or `claim`.
3. Method security checks the scope (`create`, plus `claim` for a custom code) and that the owner argument is the
   caller's own client id.
4. The service validates the URL again as a domain rule, then allocates a code: a generated one is drawn with
   `SecureRandom` and inserted with `INSERT ... ON CONFLICT DO NOTHING`. If the insert reports a conflict it draws
   again, up to five times, then fails with `ShortCodeExhaustionException`. A custom code gets one attempt and a
   conflict is `409`. Codes `api`, `actuator` and `error` are reserved so they cannot shadow routes.
5. `201` with the link and a `Location` header holding the short URL, built from the forwarded host and scheme.

**Redirect** (`GET /{code}`) is public. It looks the link up in the [redirect cache](#redirect-cache) and, on a miss,
loads it by primary key, then answers `302`, `404` when unknown or `410` when disabled. It is the hot path: a memory
hit, or one indexed read.

**Disable** (`DELETE /api/short-links/{code}`) loads the link through `ManageableLinks`, whose `@PostAuthorize` lets
only the owner or an administrator see it. Anyone else gets `404`, so the existence of a link is not revealed. The
update is `COALESCE`-based, so disabling twice keeps the first actor and time.

**List** (`GET /api/short-links`) takes a Spring `Pageable` (`page`, `size`, `sort`), capped at 200 items and
configured with `spring.data.web.pageable.*`. A client lists its own links; an administrator can list one client's or
all. The repository fetches `size + 1` rows to know whether another page follows without counting, and returns a
`Slice`. Only `createdAt` and `shortCode` are sortable, from a whitelist that maps to columns, so a sort parameter can
never reach SQL as text. Ties break on the short code so pages never overlap.

## Persistence

Postgres 18 with plain JDBC through `JdbcClient`. JPA was dropped on purpose: the access pattern is two statements,
and writing the SQL keeps `ON CONFLICT`, `RETURNING` and collation under direct control.

`short_link` has the short code as its natural primary key. Constraints keep bad data out whatever writes it: the
code must match `^[A-Za-z0-9_-]{3,32}$`, the target must start with `http://` or `https://`, and `disabled_at` and
`disabled_by` are both null or both set.

The listing indexes are `(created_at DESC, short_code COLLATE "C" DESC)`, and the same behind `created_by` for the
per-client listing. `COLLATE "C"` matters: the stock Postgres image uses `en_US.utf8`, which orders `Z` and `a`
differently from the bytes, so sorting by code would not be deterministic across databases without it.

Migrations are Flyway `V1` to `V5`. The Flyway class scanner prints "Unable to scan location /db/migration" in the
native image; it is harmless and migrations are applied from the registered resources (checked on an empty
database).

A `DataAccessResourceFailureException` is mapped to `StorageUnavailableException`, which becomes `503` with
`Retry-After: 5`, so a database outage reads as retryable rather than as a server bug.

## Concurrency

Requests run on virtual threads (`spring.threads.virtual.enabled`), so blocking JDBC does not tie up platform
threads. Concurrency against the database is bounded by the Hikari pool (10 connections, 3 s wait). Two things follow:

- Throughput is capped by how fast 10 connections can serve queries, not by threads. The benchmark confirms the pool is
  what saturates first on the JVM, which is why redirects are cached.
- Virtual threads make it easy to accept far more concurrent requests than the pool can serve. Each open connection
  holds about 150 KB of Tomcat buffers on the heap, so concurrency is bounded where it enters: Tomcat accepts at most
  `server.tomcat.max-connections` (500) connections and queues `accept-count` (100) more, and the rest are refused. That
  sheds load instead of exhausting memory (see [Performance](#performance)). Size it so that connections times 150 KB
  stays well inside the heap, and change it with `SERVER_TOMCAT_MAXCONNECTIONS`.

Shutdown is graceful: a 5 s `preStop` sleep lets the endpoint be removed from the Service, then Spring waits up to 20 s
for in-flight requests, inside the 30 s termination grace period.

## Redirect cache

Redirects were the hot path and each cost a database read, so `DefaultShortLinkService.resolve` goes through a
`RedirectCache`: an in-process Caffeine cache, one per instance.

- **What is cached.** Only active links. An unknown code is never cached, so a link works the moment it is created, and
  a disabled link is never cached, so a takedown is not extended by the cache. The cache sits on `resolve` alone: `get`,
  `list` and `disable` read the repository, so the checks that decide who may see or change a link never see a stale
  link.
- **How long.** Entries live for `shortener.shortlink.redirect-cache.ttl` (30 seconds by default). The instance that
  disables a link evicts it at once. Other instances keep serving it until their entry expires, so a takedown reaches
  every instance within the TTL. Shorten the TTL to tighten that window; nothing else synchronises instances.
- **How many.** At most `max-entries` (100,000 by default), evicted by Caffeine's admission policy. A link is a few
  hundred bytes, so that is tens of megabytes at most. `enabled: false` sends every redirect to the database.
- **Misses.** Concurrent misses for one code run a single load and share the result, so a popular new link costs one
  query, not one per waiting request. A miss for an unknown or disabled code does a second lookup to tell the two apart,
  because only active links are stored.
- **Metrics.** `cache_gets_total` (hit and miss), `cache_size` and `cache_evictions_total` for the cache
  `shortlink.redirect`, charted in the dashboard.

Rejected alternatives: Redis or another shared cache (an operational dependency and a network hop for a problem one
process can solve), cross-instance invalidation with Postgres `LISTEN/NOTIFY` (more machinery and a new failure mode for
a bounded staleness that is acceptable), and caching 404s or 410s (it delays new links or takedowns).

## Security

### Filter chain

Stateless and deny-by-default. In order: IP rate limit, bearer or DPoP authentication, client rate limit, then
authorization. `/api/**` needs authentication, `GET` and `HEAD` on `/{code}` and the health and Prometheus endpoints
are public, everything else is denied. Responses carry a restrictive CSP, `Referrer-Policy: no-referrer` and the other
default Spring Security headers.

### Tokens

Access tokens are JWTs from Keycloak, validated locally against its JWKS: signature, issuer, audience
(`shortener-api`), expiry and the JOSE type `at+jwt` (RFC 9068). There is no call to the identity provider per
request. The service is issuer-agnostic: any provider that issues these tokens works, and Keycloak is the reference.
The client is identified by the `azp` claim, and that string is stored as the owner of every link it creates.

Only the `Authorization` header carries a token. A token in the query string or a form body is ignored (tested).

### DPoP (RFC 9449)

By default every access token must be bound to the client's key:

1. The client authenticates to the token endpoint with a signed assertion (`private_key_jwt`, ES256) and a DPoP proof,
   and receives a token whose `cnf.jkt` is the thumbprint of its key.
2. Each API call sends `Authorization: DPoP <token>` and a `DPoP` header holding a proof JWT for that exact request: the
   method (`htm`), the URL without the query (`htu`), the hash of the token (`ath`), a unique `jti` and the time.
3. Spring Security checks the proof's signature, that its key matches `cnf.jkt`, the method, the URL, the token hash,
   the age and that the `jti` has not been seen.

A stolen token is useless without the private key, and the `Bearer` scheme is refused outright, even for a valid token.
Behind the gateway, `htu` is built from the forwarded host and scheme, which is why forwarded headers are handled by
Tomcat's trust-aware valve.

Limits to know about: the replay cache is in memory and per pod, a proof is remembered for 30 seconds, and Spring's
cache accepts at most 1,000 live proofs per client key, which is about 30 requests a second per key. DPoP nonces are
not supported. With `shortener.security.dpop.required=false` plain bearer tokens are accepted too.

Failures come back as `401` with `WWW-Authenticate: DPoP realm="shortener", error="…", algs="…"`. The error is
`invalid_token` for a bad token or the wrong scheme, `invalid_request` for a missing proof and `invalid_dpop_proof`
for a bad one. `/.well-known/oauth-protected-resource` (RFC 9728) tells clients the authorization server and that DPoP
is required.

### Authorization

Scopes decide what a client may do; their names are configuration. The checks are declared on the service with Spring
method security rather than written in code, using meta-annotations (`@MayCreate`, `@MayClaim`, `@MayRead`, `@MayList`,
`@MayDisable`):

| Scope | Allows |
|---|---|
| `shortlinks:create` | create with a generated code |
| `shortlinks:claim` | also choose the code (needs `create` too) |
| `shortlinks:read`, `shortlinks:delete` | read and disable the client's own links |
| `shortlinks:admin` | read and disable any client's links |

Ownership is a rule on top of scopes: an argument such as `createdBy` must equal the caller's client id
(`#createdBy == authentication.name`), and a `PermissionEvaluator` lets a caller manage a link only if it created it or
is an administrator. A denial on a link read is turned into `404` by `@HandleAuthorizationDenied`.

### Rate limiting

Token buckets in memory (Bucket4j over a size-bounded Caffeine cache): 300 requests a minute per IP before
authentication, and 60 a minute per client after it. State is per pod, so with several replicas the effective limit
multiplies; a global limit would need a shared store. Actuator paths are never limited so probes cannot be starved.

### Identity provider and OAuth 2.1

The realm in `deploy/keycloak/shortener-realm.json` defines the scopes, the audience mapper and four clients (`demo`,
`other`, `admin` and `no-scope`), all confidential, service-account only, with implicit and password grants off.
Against the OAuth 2.1 draft this covers: bearer tokens only in the header, no deprecated grants, short-lived tokens (five
minutes), sender-constrained tokens and asymmetric client authentication. The draft is not final, so this is alignment
rather than conformance.

## Observability

- **Metrics**: `/actuator/prometheus` on the management port 8081, which is cluster-internal. The key series is
  `http_server_requests_seconds` as a histogram tagged by route template and status, so short codes never become label
  values (tested). Hikari, JVM and process metrics come with it.
- **Dashboard**: `deploy/observability/grafana/dashboards/shortener.json` has twelve panels: request rate by status,
  redirect and create percentiles, rejected requests, the pool, acquire time, heap, GC, CPU and threads. A request panel
  stays empty until traffic arrives, because Prometheus has no series for an event that has not happened. The native image
  has no garbage-collector beans, so its GC panel is empty and its heap maximum reads zero; the JVM fills both.
- **Logs**: structured ECS JSON in Kubernetes.
- **Health**: liveness, and readiness that includes the database, on the management port.
- **Traces**: Micrometer Tracing with the OpenTelemetry bridge, exported over OTLP/HTTP. Every request has a server span
  named by its route (`http get /{shortCode}`), joined to the caller's trace through the W3C `traceparent` header. Two
  child spans mark the database calls: `shortlink.load` for a redirect that missed the cache, so its absence on a
  redirect means a cache hit, and `shortlink.insert` for each attempt to store a link, so a collision shows as a second
  insert. They are also timers (`shortlink_load_seconds`, `shortlink_insert_seconds`). Trace and span ids are in the
  logs. Spring Security's per-filter observations are switched off, because they would add a span per filter per request.
  Nothing is sampled by default (`management.tracing.sampling.probability: 0.0`), so running without a collector logs no
  export errors; set the rate, and optionally `MANAGEMENT_OPENTELEMETRY_TRACING_EXPORT_OTLP_ENDPOINT` (default
  `http://localhost:4318/v1/traces`), to turn it on. The exporter is always built in, and the endpoint has a default in
  `application.yaml`, because in a native image Spring decides at build time which beans exist: the exporter needs the
  endpoint property to be present when the image is built. Switching the exporter off with a property, or supplying the
  endpoint only at run time, compiles it out and the image exports nothing without any error. Environment variables
  still override the value at run time. The OTLP exporters for logs and metrics are off. Locally, the compose `observability` profile runs Tempo, wired to Grafana as a
  data source; start the app with `MANAGEMENT_TRACING_SAMPLING_PROBABILITY=1.0` to see every request. The production
  overlay samples 5% and exports to `otel-collector.monitoring:4318` through the optional `shortener-telemetry` config map.

Not done: alert rules, metrics from the Envoy proxies, and Envoy's own spans, so a trace starts at the application.

## Native image

Built with Paketo and Liberica NIK (`./gradlew bootBuildImage`), with `-march=compatibility` so it runs on any x86-64
node, and `-J-Xmx7g` for the build, which needs about 7 GB free. Building while the machine is busy is killed by the
OOM killer (exit 137). The image is 367 MB and starts in 0.3 to 0.4 seconds.

What had to be taught to the native image, each found by running it:

| Failure | Cause | Fix |
|---|---|---|
| Rate limiter cannot build its cache | Caffeine picks generated classes by name | `CaffeineRuntimeHints` registers them all |
| `management.server.port` ignored | the port is read at AOT time | set it in `application.yaml`, not the environment |
| Tomcat fails with a missing reflection entry | `server.tomcat.mbeanregistry.enabled=true` | removed; with virtual threads the thread metrics read -1 anyway |
| Every DPoP request is `401` with no reason | Spring Security adds its DPoP filter only if `ClassUtils.isPresent(...)` finds a class, which fails for classes not registered | `DpopRuntimeHints`, plus a startup check that refuses to start if DPoP is required and the filter is missing |
| Authorized calls fail with `500` | SpEL reads `authentication.name` by reflection | `AuthorizationRuntimeHints` registers the token classes |

The unit tests cover the hints but cannot run a native image, so `perf/smoke.sh` exercises every endpoint with real
tokens against it (21 checks).

For diagnosis, `./gradlew bootBuildImage -PnativeProfiling` builds an image with JFR and heap dumps enabled
(`shortener:<version>-profiling`).

## Kubernetes

`deploy/k8s`:

- `base`: Deployment (two replicas, rolling update with no unavailability, topology spread, non-root, read-only root
  filesystem, all capabilities dropped, startup, liveness and readiness probes on the management port), Service,
  ServiceAccount and PodDisruptionBudget.
- `gateway`: Envoy Gateway resources shared by both overlays: an `EnvoyProxy` (two replicas, a disruption budget, source
  address preserved), a `ClientTrafficPolicy` (header and idle timeouts) and a `BackendTrafficPolicy` (request bodies
  capped at 16 KiB, 15 s request timeout). `gatewayclass` is cluster-wide and applied once.
- `overlays/local`: kind, with Postgres and Keycloak inside the cluster, the proxy as a NodePort mapped to
  `localhost:8088`, and `/realms` routed to Keycloak.
- `overlays/production`: its own namespace under the `restricted` pod-security level, TLS through cert-manager with an
  HTTP to HTTPS redirect, an HPA (2 to 10 on CPU), a NetworkPolicy (only the gateway reaches port 8080 and only the
  monitoring namespace reaches 8081), database credentials from an ExternalSecret, and a PodMonitor.

The production overlay assumes Envoy Gateway, cert-manager with Gateway API support enabled, External Secrets and the
Prometheus operator. The replica count is left to the HPA. Egress is open on ports 5432 and 443 because the database and
identity provider addresses are environment specific; tighten it with those addresses.

Tested on kind with stand-in backends: routing, forwarded address handling, the body cap and the request timeout.
Not tested: cert-manager issuance, a real Keycloak behind the gateway, and the PodMonitor.

## Performance

### Method

`perf/bench.sh` runs one build of the app in a container with 2 CPUs (cores 0 and 1) and 512 MB, the production memory
limit. Postgres runs on cores 2 to 5 and k6 on 6 to 9, so none of them competes for the same CPU. k6 uses an
arrival-rate executor (so slow responses do not slow the load down), a mix of 99% redirects against a hot subset and
1% creates, each create signed with DPoP. Each variant warms up for 30 seconds, then runs 1,500, 5,000 and 10,000
requests a second. Server-side percentiles come from Prometheus. `perf/run-all.sh` runs the variants and prints the
table.

This is one laptop, one run per rate, with the database and load generator on the same machine. Treat the numbers as
shape, not as capacity.

### Results

| | JVM (Temurin 25) | native |
|---|---|---|
| ready after `docker run` | 5.4 s | 0.7 s |
| memory at rest | 250 MiB | 210 MiB |
| 1,500 req/s, redirect p99 | 1.0 ms | 4.6 ms |
| 5,000 req/s served | 4,942 | 3,264 (1.6% failed) |
| 5,000 req/s, redirect p99 | 2.4 ms | 3,018 ms |
| 10,000 req/s | overload: 713 served | overload: 318 served |

With the [redirect cache](#redirect-cache) (JVM, same method, but only 1,500 and 5,000 requests a second because
saturation runs can take the development machine's network down): redirect p99 of 1.0 ms at both rates, where it was 2.4
ms at 5,000 before, no failures, and the 5,000 req/s run used 32% of the two cores. The ceiling was not probed, and the
native image was not benchmarked with the cache. Results are in `perf/results/2026-10-03-cache`.

On the JVM, a database read per redirect and the pool of 10 are the limit: at 10,000 requests a second the pool times
out while CPU stays low. These figures predate the [redirect cache](#redirect-cache), which removes that read.

### Native image under overload: diagnosis and fix

The native image loses its heap at 5,000 requests a second. It was profiled with JFR, GC logging and a heap dump.

- **No leak.** At a steady 1,500 requests a second for four minutes the live heap after collection stayed flat at 27 to
  33 MB, and collections took 4.6% of the time.
- **Retention follows connections.** The heap dump taken at the `OutOfMemoryError` had exactly 3,000 Tomcat requests and 3,000
  socket wrappers, which is the number of clients k6 was running. About 450 MB of 453 MB was byte and char arrays in
  roughly 8 to 16 KB blocks, which is Tomcat's per-connection buffers, about 150 KB per connection with its request
  objects.
- **A feedback loop.** Once the native image is a little slower, an open-model client opens more connections, each costs
  heap, collections take longer (up to 2.8 s pauses and half the run in GC), and it slows further until the heap is
  full. The JVM stays below that point, so its connection count stays small.
- **Collector.** The native image uses the serial collector with a young generation of 10% of the heap. At 1,500
  requests a second that is 21 young and 0.7 full collections a second. Enlarging the young generation to 30% did not
  help and was worse in the second run.
- **Allocation.** 12.7% of sampled allocation is virtual-thread stack copies (`StoredContinuation`), and Micrometer's
  observation plumbing, including one observation per Spring Security filter, is another visible share of allocation and
  CPU.

Two changes closed it, each measured on the native image with the same limits:

- **The redirect cache.** Without the database read the native image holds its latency, so clients do not pile up. At
  5,000 requests a second it now serves 4,936 a second with no failures, a redirect p99 of 1.0 ms and a peak of 142 MiB,
  where it failed. The connection limit made no difference at that load: limits of 8192, 1000, 500 and 250 all gave the
  same throughput and latency.
- **The connection limit.** It matters beyond capacity. At 12,000 requests a second, about 2.4 times what two cores
  sustain:

  | | no limit (8192) | `max-connections: 500` |
  |---|---|---|
  | peak memory | 514 MiB (the limit) | 208 MiB |
  | time in GC, longest pause | 14.3%, 2.6 s | 3.9%, 60 ms |
  | `OutOfMemoryError` | 3 | 0 |
  | redirect p99 (server) | 4,144 ms | 1.0 ms |
  | requests served, failed | 5,837 a second, 0.08% | 4,605 a second, 1.08% |

  The limit gives up some throughput and fails about 1% of requests, which is the load it sheds, and in return memory and
  latency stay flat. Creates still queue for seconds at that load because the CPU is saturated, so a limit is protection,
  not extra capacity.

Not yet tested: turning off Spring Security observations (`management.observations.enable.spring.security=false`), which
profiling showed as a visible share of allocation. Tooling: `perf/profile.sh`, `perf/gc-summary.py`,
`perf/hprof-histogram.py` and `perf/tune-connections.sh`.

### Running load tests safely

The development machine runs an application firewall (Safing Portmaster) that inspects new connections through a
kernel packet queue. Overload runs create connection storms that overflow it: the kernel logs
`nfnetlink_queue: nf_queue: full ... dropping packets`, the queue does not drain, and every new connection on the
machine fails until reboot. This took the network down three times. Keep to sustainable rates, prefer host networking
and loopback to the Docker bridge, and pause such a firewall before saturation tests. Check
`journalctl -k | grep nf_queue` when connections start timing out.

## Decisions

**Plain JDBC instead of JPA.** Two statements dominate and both need SQL features JPA hides. The persistence adapter is
the only place that knows SQL.

**Spring facilities over bespoke code.** Authorization is method security with a `PermissionEvaluator`; pagination is
`Pageable` and `Slice`; the resource server, DPoP and RFC 9728 metadata are Spring Security's. Custom code is limited to
what Spring does not offer: the Bearer refusal, the problem-detail responder, rate limiting and the hints.

**Virtual threads, not coroutines or structured concurrency.** Each request does one blocking query, so there is nothing
to fan out for structured concurrency to scope, and coroutines would still need a thread or a continuation per in-flight
request, plus a dispatcher to run the blocking JDBC call on. The native image's problem was never scheduling: it was
about 150 KB of Tomcat buffers per open connection and the collector (see Performance), which neither changes. Bounding
concurrency (connection limits) and removing the database read (the cache) address the measured causes. Revisit if a
request ever calls several things in parallel.

**Ownership is the client id.** There are no end users, only service clients, so the token's `azp` is the owner and
scopes are the only permission model.

**Sender-constrained tokens by default.** A leaked bearer token is the main risk for a token-based API; DPoP removes it
at the cost of a client that can sign. A JDK-only client is provided.

**Redirect cache.** In-process Caffeine rather than Redis, caching active links only, with a short TTL and local
eviction on disable. See [Redirect cache](#redirect-cache) for the behaviour and what was rejected.

## Limitations

- A link disabled on one instance can still redirect on the others for up to the cache TTL (30 seconds by default).
- Rate limits and the DPoP replay cache are per pod; one DPoP key is capped at about 30 requests a second.
- Beyond capacity (about 5,800 requests a second on two cores) the service sheds load rather than slowing everything
  down, but creates still queue for seconds; the Envoy proxies do not yet limit connections to match Tomcat's.
- The Envoy Gateway, cert-manager and Prometheus operator parts are untested on a real cluster.
- No alert rules, Envoy metrics or Envoy spans; no CI.
- The dev keys and secrets are committed deliberately and are public.
