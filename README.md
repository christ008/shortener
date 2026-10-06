# shortener

[![CI](https://github.com/christ008/shortener/actions/workflows/ci.yml/badge.svg)](https://github.com/christ008/shortener/actions/workflows/ci.yml)

A URL shortener with OAuth2 DPoP-bound tokens, per-client ownership, Postgres, an in-memory redirect cache, Prometheus and
OpenTelemetry, a GraalVM native image, and a hardened Docker Compose / Swarm stack.

It has not served real traffic. Every statement about load comes from a synthetic workload on one machine.

Spring Boot 4.1 · Kotlin 2.3 · Java 25 · Postgres 18 · Keycloak 26 · nginx · Apache-2.0

## Behaviour

- Creates a link with a generated code (7 base62 characters) or a custom one. `GET /{code}` answers `302`.
- A client lists, reads and disables its own links. An administrator can act on any.
- Another client's link answers `404`. A disabled link answers `410` and keeps its code.
- A takedown reaches every instance within the cache TTL (30 s).
- Every authentication, authorization and rate-limit failure is an RFC 9457 problem detail.

## Run it locally

Needs Docker and JDK 25. The DPoP client needs JDK 17 or newer.

```bash
deploy/keycloak/dev-setup     # once: generates keys and passwords (builds its tools the first time)
./gradlew bootRun             # starts Postgres and Keycloak from compose.yaml, then the app on :8080
```

```bash
java deploy/keycloak/DpopClient.java call deploy/keycloak/dev-keys/demo-client.jwk.json demo-client \
  POST http://localhost:8080/api/short-links '{"targetUrl":"https://example.com/some/long/path"}'
curl -i http://localhost:8080/<shortCode>
```

`dev-setup` writes git-ignored keys, a dev realm and `.env`. These are throwaway: never use them anywhere real.

`./gradlew tasks --group tooling` lists a task for every script. Details: [docs/OPERATING.md](docs/OPERATING.md).

Observability: `docker compose --profile observability up -d`, then <http://localhost:3000/d/shortener/shortener>.
See [docs/OBSERVABILITY.md](docs/OBSERVABILITY.md).

## API

| Request | Needs | Answers |
|---|---|---|
| `POST /api/short-links` | `shortlinks:create` (`shortlinks:claim` too for `customCode`) | `201` with the link, `400`, `409` code taken or reserved |
| `GET /api/short-links?page&size&sort` | `shortlinks:read` | `200` with `items`, `page`, `size`, `hasNext`, `totalItems`, `totalPages`; sort by `createdAt` or `shortCode` |
| `GET /api/short-links/{code}` | `shortlinks:read` | `200`, or `404` |
| `DELETE /api/short-links/{code}` | `shortlinks:delete` | `204` (idempotent), or `404` |
| `GET /{code}` | nothing | `302`, `404`, or `410` when disabled |

- `shortlinks:admin` reads and disables any client's links.
- Errors: `401` with a `DPoP` challenge, `403` with `insufficient_scope`, `429` and `503` with `Retry-After`.
- Scope names are configuration (`shortener.shortlink.scopes.*`).
- Contract: [docs/openapi.yaml](docs/openapi.yaml). A test fails if it drifts from the code.

## Configuration

Defaults suit local development. Deployed, set `SPRING_PROFILES_ACTIVE=production` (JSON logs, no internals in errors or
health, 5% trace sampling, DPoP required) and:

| Variable | Meaning |
|---|---|
| `SPRING_DATASOURCE_URL`, `_USERNAME`, `_PASSWORD` | Postgres, as `shortener_app` |
| `SPRING_FLYWAY_URL`, `_USER`, `_PASSWORD` | Postgres, as `shortener_migrator`. Set only on the migration job. With `SHORTENER_MIGRATE_ONLY=true` the process migrates and exits |
| `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUERURI`, `_JWKSETURI`, `_AUDIENCES` | the identity provider |
| `SHORTENER_SECURITY_DPOP_REQUIRED` | `true` by default. `false` also accepts bearer tokens (development and tests only) |
| `SHORTENER_SECURITY_RATELIMIT_PERIP_CAPACITY`, `..._PERCLIENT_CAPACITY` | requests a minute per IP (300) and per client (60) |
| `SERVER_TOMCAT_MAXCONNECTIONS` | connections accepted before refusing (500), about 150 KB of heap each |
| `SHORTENER_SHORTLINK_TARGETURLS_ALLOWEDHOSTS` | comma-separated hosts links may point to: `example.com`, `*.example.org` (subdomains only). Under `production` the service does not start without this or `..._ALLOWANY=true` |
| `SHORTENER_SHORTLINK_REDIRECTCACHE_TTL`, `..._MAXENTRIES`, `..._ENABLED` | cache entry lifetime (30s), size (100,000), on/off |
| `SHORTENER_SHORTLINK_REDIRECTCACHE_STALEIFERROR` | how long a read link is still followed while the database is unreachable (5m); `0` turns it off |

Write names with no separator inside a word: `SPRING_DATASOURCE_HIKARI_CONNECTIONTIMEOUT`, not `..._CONNECTION_TIMEOUT`.
The latter fails at start with `The configuration of the pool is sealed once started`.

## Build, test, package

```bash
./gradlew test                  # integration tests use Testcontainers (Docker)
./gradlew bootBuildImage        # native image on Alpaquita (musl); about 7 GB free, 3 minutes
perf/smoke.sh                   # every endpoint, with real tokens, against a running instance
```

The image runs as uid 1000, is ready 0.4 to 0.7 s after `docker run`, and has `/workspace/health-check` for container health checks.

## Deploy

`compose.prod.yaml` is the production stack, for Docker Swarm or one host with Compose: an nginx edge with TLS, two
application tasks, a migration job, Postgres, and optional overlays for Prometheus and Keycloak.

- Runbook: [docs/DEPLOY.md](docs/DEPLOY.md).
- Architecture and trade-offs: [docs/INTERNALS.md](docs/INTERNALS.md#deployment).

## Performance

Measured on one laptop, one run per rate. The app ran with 2 cores and 512 MB, with Postgres and k6 on other cores.
The load was 99% redirects and 1% creates, with redirects spread over 300 links that all fit in the cache.

- Native image and JVM both served 5,000 req/s with no failures and a redirect p99 of 1 ms.
- Ready 0.4 to 0.7 s after `docker run` (native, twelve runs) and 5.4 to 5.6 s (JVM, two runs).
- Not measured: redirects of links the cache has not seen, and rates above 5,000 req/s with the cache.

Method and numbers: [docs/INTERNALS.md](docs/INTERNALS.md#performance), including a warning to read before load testing.

## Layout

| Path | Contents |
|---|---|
| `src/main/kotlin/uy/ct/shortener/shortlink` | public contract (types, service and repository interfaces, exceptions) and `internal/` adapters |
| `src/main/kotlin/uy/ct/shortener/security` | resource server, DPoP, rate limiting, problem details |
| `compose*.yaml`, `deploy/` | dev and production stacks, edge, Postgres setup, Keycloak realm and image, alert rules, Grafana, Tempo |
| `tools/` | Kotlin tools behind the scripts: `dev-setup`, realms, smoke test, report reader |
| `perf/` | k6 workload, benchmark and profiling scripts, results |
| `.github/workflows/` | CI on every push, release on `v*` tags, manual native image build |

| Document | Contents |
|---|---|
| [OPERATING.md](docs/OPERATING.md) | run, call, test and look after it |
| [DEPLOY.md](docs/DEPLOY.md) | production stack runbook |
| [OBSERVABILITY.md](docs/OBSERVABILITY.md) | metrics, dashboard, traces, alerts, logs |
| [INTERNALS.md](docs/INTERNALS.md) | how it works and why |
| [adr/](docs/adr/README.md) | decision records |
| [THREAT_MODEL.md](docs/THREAT_MODEL.md) | STRIDE, OWASP, open findings |
| [CLOUD.md](docs/CLOUD.md), [UI.md](docs/UI.md) | plans for a public instance and a web UI (nothing built) |
| [openapi.yaml](docs/openapi.yaml) | API contract |

Releases are tagged `v0.x.0`, one minor version per change.

## License

Copyright 2026 Christian Tejeda. Licensed under the [Apache License, Version 2.0](LICENSE).
