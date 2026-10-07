# shortener

[![CI](https://github.com/christ008/shortener/actions/workflows/ci.yml/badge.svg)](https://github.com/christ008/shortener/actions/workflows/ci.yml)

**TL;DR** A URL shortener designed to face the public internet: secure by default, and modern from the language to the
deployment.

- **Secure by default.** An OAuth2 resource server whose tokens are bound to the client's key (DPoP, RFC 9449), so a stolen
  token is useless. Every client sees only its own links. Production refuses to start without a decision on which target
  hosts links may point to, with DPoP off, or without a nonce secret shared by its instances.
- **A link cannot be hijacked.** A code is never handed out twice: in the stack, the application's database role cannot delete a row or
  change a code. A disabled link answers `410` and keeps its code.
- **Fast, and light.** A redirect needs no database write, and an in-memory cache serves it. As a GraalVM native image it is
  ready in under a second, on virtual threads.
- **Made to be operated.** A hardened Docker Compose / Swarm stack with an nginx edge and TLS, migrations in a job of their
  own, signed and scanned releases, Prometheus, OpenTelemetry and a Grafana dashboard.
- **Argued, not asserted.** A STRIDE threat model, one record per decision with what it costs, and tests that hold the
  invariants below.

> [!IMPORTANT]
> Designed for the public, not yet run for it: the service has not served real traffic, and every statement about load comes
> from a synthetic workload on one machine. What a public instance still needs is in [docs/CLOUD.md](docs/CLOUD.md).

<p align="center">
  <img src="docs/images/dashboard.png" alt="Grafana dashboard of the service at 1,500 requests a second" width="420">
</p>

Spring Boot 4.2 · Kotlin 2.4 · Java 25 · Postgres 18 · Keycloak 26 · nginx · Apache-2.0

## Behaviour

- Creates a link with a generated code (7 base62 characters) or a custom one. `GET /{code}` answers `302`, not `301`, so a
  browser does not keep following a link after a takedown.
- A client lists, reads and disables its own links. An administrator can act on any.
- Another client's link answers `404`. A disabled link answers `410` and keeps its code. A code is never handed out twice.
- A takedown reaches every instance within the cache TTL (30 s).
- Every authentication, authorization and rate-limit failure is an RFC 9457 problem detail.

## Invariants

What must stay true, and the test or decision that holds each one: [docs/DESIGN.md#invariants](docs/DESIGN.md#invariants).

- **Short codes.** A code is never reused. A disabled link answers `410`. A redirect never needs a database write. Staleness is
  bounded by the cache TTL (by `stale-if-error` while the database is down). The application's database credentials cannot
  change the schema.
- **Security.** Production must decide where links may point. DPoP is required. Link ownership is enforced on the server.
  Development keys are generated on the machine that uses them. Production secrets are never repository configuration.
- **Operations.** An invalid production security configuration stops the start. Migrations run apart from the application.
  Readiness and liveness are different questions.

## Run it

Needs Docker and JDK 25. The reference DPoP client needs JDK 17 or newer.

```bash
deploy/keycloak/dev-setup     # once: generates keys and passwords (builds its tools the first time)
./gradlew bootRun             # starts Postgres and Keycloak from compose.yaml, then the app on :8080
```

> [!WARNING]
> `dev-setup` writes git-ignored keys, a dev realm and `.env`. These are throwaway: never use them anywhere real.

Then call it, rehearse the production stack on your machine, and go to production with a checklist:
[docs/OPERATING.md](docs/OPERATING.md). Metrics, dashboard and traces: `docker compose --profile observability up -d`, then
<http://localhost:3000/d/shortener/shortener> ([OBSERVABILITY.md](docs/OBSERVABILITY.md)).

## API

| Request | Scope | Success | Errors |
|---|---|---|---|
| `POST /api/short-links` | `shortlinks:create` | `201` with the link and its `Location`. The code is generated, and `shortUrl` is the URL to share | `400` |
| `PUT /api/short-links/{code}` | `shortlinks:create` and `shortlinks:claim` | for a code you choose: `201`, or `200` when you already have this link, so repeating is safe | `400`; `409` when the code is reserved, taken, or yours for another target |
| `GET /api/short-links?page&size&sort` | `shortlinks:read` | `200` with `items`, `page`, `size`, `hasNext`, `totalItems` and `totalPages`. Sort by `createdAt` or `shortCode` | none of its own |
| `GET /api/short-links/{code}` | `shortlinks:read` | `200` | `404` |
| `PATCH /api/short-links/{code}` with `{"disabled": true}` | `shortlinks:delete` | `200` with the link, disabled. Repeating is safe | `400`; `404` |
| `GET /{code}` | none | `302` | `404`; `410` when disabled |

- `shortlinks:admin` reads and disables any client's links.
- Errors: `401` with a `DPoP` challenge, `403` with `insufficient_scope`, `429` and `503` with `Retry-After`.
- Scope names are configuration (`shortener.shortlink.scopes.*`).
- Contract: [docs/openapi.yaml](docs/openapi.yaml). A test fails if it drifts from the code.

## Configuration

Defaults suit development. A deployment sets `.env` and a few secret files, and the `production` profile (JSON logs, no internals
in errors or health, 5% trace sampling, DPoP required) is set by the stack. Every variable, where it goes and what reads it:
[docs/OPERATING.md#configuration](docs/OPERATING.md#configuration).

## Evidence

- **Tests:** 380, all passing, 255 of them on the service. Line coverage 96.9%, branch coverage 87.5%.
- **Mutation score:** 92% on the core logic (95 mutants), with the survivors explained.
  [INTERNALS.md#testing](docs/INTERNALS.md#testing)
- **Security:** the controls, and a STRIDE model with a register of findings, severities, fix dates and the risks accepted.
  [SECURITY.md](docs/SECURITY.md), [THREAT_MODEL.md](docs/THREAT_MODEL.md)
- **Performance:** one laptop, a synthetic load of 99% redirects, 5,000 req/s with a redirect p99 of 1 ms, for links the cache holds.
  The figure is for the cache, not the database. Method and a warning to read before load testing:
  [INTERNALS.md#performance](docs/INTERNALS.md#performance).
- **Decisions:** one record per decision, with what it costs. [DESIGN.md](docs/DESIGN.md#decisions), [docs/adr/](docs/adr/README.md)

## Build, test, package

```bash
./gradlew test                  # integration tests use Testcontainers (Docker)
./gradlew bootBuildImage        # native image on Alpaquita (musl); about 7 GB free, 3 minutes
perf/smoke.sh                   # every endpoint, with real tokens, against a running instance
```

## Deploy

`deploy/stack/compose.prod.yaml` is the production stack, for Docker Swarm or one host with Compose: an nginx edge with TLS, two
application tasks, a migration job, Postgres, and optional overlays in `deploy/stack/overlays/` for Prometheus, Keycloak and
Postgres backups with a replica. `deploy/stack/deploy.sh` verifies the image's signature before it deploys.

| Document | Contents |
|---|---|
| [OPERATING.md](docs/OPERATING.md) | develop, rehearse production, a go-live checklist, every configuration key |
| [DEPLOY.md](docs/DEPLOY.md) | production stack runbook: first deploy, updates, Keycloak, backups |
| [OBSERVABILITY.md](docs/OBSERVABILITY.md) | metrics, dashboard, traces, alerts, logs |
| [INTERNALS.md](docs/INTERNALS.md) | how it works: request flows, database, cache, native image, deployment, tests and measurements |
| [DESIGN.md](docs/DESIGN.md) | invariants, and why the code has this shape: modules, types, SOLID review, decisions |
| [SECURITY.md](docs/SECURITY.md) | the controls: filter chain, tokens, DPoP, scopes, rate limits |
| [THREAT_MODEL.md](docs/THREAT_MODEL.md) | STRIDE, OWASP, a register of findings with their fixes, and the risks accepted |
| [adr/](docs/adr/README.md) | decision records |
| [CLOUD.md](docs/CLOUD.md), [UI.md](docs/UI.md) | plans for a public instance and a web UI (nothing built) |
| [openapi.yaml](docs/openapi.yaml) | API contract |

Releases are tagged `v0.x.0`, one minor version per change.

## License

Copyright 2026 Christian Tejeda. Licensed under the [Apache License, Version 2.0](LICENSE).
