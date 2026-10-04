# shortener

A URL shortener built to production standards: OAuth2 with sender-constrained (DPoP) tokens, per-client ownership,
plain JDBC on Postgres, virtual threads, Prometheus metrics, a GraalVM native image and Kubernetes manifests with the
Gateway API.

Spring Boot 4.1 · Kotlin 2.3 · Java 25 · Postgres 18 · Keycloak 26 · Envoy Gateway · AGPL-3.0

## What it does

- Creates short links with a generated code (7 base62 characters) or a custom one, and redirects with a `302`.
- Each client owns the links it creates. It can list, read and disable its own links; an administrator can act on any.
  A link that belongs to someone else looks like it does not exist.
- Disabled links answer `410 Gone` and keep their code taken, so nobody can re-register it. Redirects are served from an
  in-memory cache, so a takedown reaches every instance within 30 seconds.
- Every authentication, authorization and rate-limit failure is an RFC 9457 problem detail.

## Run it locally

You need Docker and JDK 25.

```bash
./gradlew bootRun
```

`bootRun` starts Postgres and Keycloak from `compose.yaml` and applies the Flyway migrations. Clients authenticate with
a signed assertion and get tokens bound to their key, so use the JDK-only client in `deploy/keycloak`:

```bash
java deploy/keycloak/DpopClient.java call deploy/keycloak/dev-keys/demo-client.jwk.json demo-client \
  POST http://localhost:8080/api/short-links '{"targetUrl":"https://example.com/some/long/path"}'
```

The response carries the short URL in `Location`. Following it needs no token:

```bash
curl -i http://localhost:8080/<shortCode>
```

Prometheus, Grafana and Tempo (for traces) are behind a profile: `docker compose --profile observability up -d`, then open
<http://localhost:3000/d/shortener/shortener>. To see traces locally, start the app with
`MANAGEMENT_TRACING_SAMPLING_PROBABILITY=1.0`; it exports to `http://localhost:4318/v1/traces` by default. The dev clients, keys and secrets in this repository are public on
purpose and must never be used anywhere real.

## API

| Request | Needs | Answers |
|---|---|---|
| `POST /api/short-links` | `shortlinks:create` (and `shortlinks:claim` for `customCode`) | `201` with the link, `400`, `409` code taken or reserved |
| `GET /api/short-links?page&size&sort` | `shortlinks:read` | `200` with `items`, `page`, `size`, `hasNext`; sort by `createdAt` or `shortCode` |
| `GET /api/short-links/{code}` | `shortlinks:read` | `200`, or `404` |
| `DELETE /api/short-links/{code}` | `shortlinks:delete` | `204`, idempotent, or `404` |
| `GET /{code}` | nothing | `302`, `404`, or `410` when disabled |

`shortlinks:admin` reads and disables any client's links. Errors are `401` with a `DPoP` challenge, `403` with
`insufficient_scope`, `429` with `Retry-After`, and `503` with `Retry-After` when the database cannot be reached.
Scope names are configuration (`shortener.shortlink.scopes.*`).

## Configuration

Everything has a default for local development. In a cluster the main settings are environment variables:

| Variable | Meaning |
|---|---|
| `SPRING_DATASOURCE_URL`, `_USERNAME`, `_PASSWORD` | Postgres |
| `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUERURI`, `_JWKSETURI`, `_AUDIENCES` | the identity provider |
| `SHORTENER_SECURITY_DPOP_REQUIRED` | `true` by default; `false` also accepts plain bearer tokens (development and tests only) |
| `SHORTENER_SECURITY_RATELIMIT_PERIP_CAPACITY`, `..._PERCLIENT_CAPACITY` | requests per minute per IP (300) and per client (60) |
| `SERVER_TOMCAT_MAXCONNECTIONS` | connections Tomcat accepts before refusing (500); each costs about 150 KB of heap |
| `SHORTENER_SHORTLINK_REDIRECTCACHE_TTL`, `..._MAXENTRIES` | how long (30s) and how many (100,000) links the redirect cache keeps; `..._ENABLED=false` turns it off |

## Build, test, package

```bash
./gradlew test                  # 123 tests; integration tests use Testcontainers
./gradlew bootBuildImage        # native image through Paketo and Liberica (needs about 7 GB free; takes 3 minutes)
```

The native image starts in about 0.4 seconds. Run `perf/smoke.sh` against it to exercise every endpoint with real
tokens.

## Deploy

`deploy/k8s` is a Kustomize base with a local overlay (kind) and a production overlay: Envoy Gateway with TLS through
cert-manager, an HPA, a disruption budget, a NetworkPolicy, secrets from an ExternalSecret, and a PodMonitor. Install
Envoy Gateway and the `gatewayclass` once per cluster. See [docs/INTERNALS.md](docs/INTERNALS.md#kubernetes).

## Performance

Measured on one laptop, with the app limited to 2 cores and 512 MB: the JVM and the native image both serve about
5,000 requests a second at a redirect p99 of 1 ms (the load generator was the limit), and the native image starts in
0.4 seconds. The redirect cache and a connection limit keep the native image stable under overload. The methodology,
the numbers and the investigation are in [docs/INTERNALS.md](docs/INTERNALS.md#performance). Read the warning there before running load tests.

## Layout

```
src/main/kotlin/uy/ct/shortener
  shortlink/            public contract: ShortLink, ShortCode, service and repository interfaces, exceptions
    internal/           service, web, persistence and authorization adapters
  security/             resource server, DPoP, rate limiting, problem details
deploy/                 Kubernetes, Keycloak realm and dev keys, Prometheus and Grafana
perf/                   k6 workload, benchmark and profiling scripts, results
docs/INTERNALS.md       how it works and why
```

Releases are tagged `v0.x.0`, one minor version per change.
