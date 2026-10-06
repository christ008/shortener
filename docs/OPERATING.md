# Operating and testing it

How to run the service on your machine, try it, test it, and look after it when it is deployed. Production setup is in
[DEPLOY.md](DEPLOY.md), what to watch is in [OBSERVABILITY.md](OBSERVABILITY.md), and the design is in
[INTERNALS.md](INTERNALS.md).

- [What you need](#what-you-need)
- [Three ways to run it](#three-ways-to-run-it)
- [Try the API](#try-the-api)
- [Test it](#test-it)
- [Look after it](#look-after-it)
- [When something is wrong](#when-something-is-wrong)

## What you need

| For | You need |
|---|---|
| Running and testing | Docker, and JDK 25 (Gradle finds one, or use SDKMAN) |
| Building the native image | about 7 GB of free memory, and 3 minutes |
| The load test | Docker (k6 runs in a container), a few spare cores, and the warning under [Test it](#test-it) |
| Trying the production stack | `openssl` and `keytool`, both of which come with a JDK and most systems |

## Three ways to run it

| | Command | Use it for |
|---|---|---|
| The JVM, from Gradle | `./gradlew bootRun` | day to day: fast restarts, debugger, the `dev` profile |
| The native image | `./gradlew bootBuildImage`, then `docker run` | checking what production runs |
| The production stack | `deploy/stack/local/prepare.sh`, then deploy | rehearsing TLS, the edge, the migration job and rolling updates |

**The JVM.** `bootRun` starts Postgres and Keycloak from `compose.yaml` and applies the migrations. The app listens on
`localhost:8080`, health and metrics on `localhost:8081`, Keycloak on `localhost:8180`. The `dev` profile samples every
trace, shows health details and relaxes the rate limits.

**The native image.** Postgres and Keycloak still come from compose:

```bash
./gradlew bootBuildImage
docker compose up -d postgres keycloak
port=$(docker compose port postgres 5432 | cut -d: -f2)
docker run --rm --network host --memory 512m \
  -e SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:$port/mydatabase \
  -e SPRING_DATASOURCE_USERNAME=myuser -e SPRING_DATASOURCE_PASSWORD=secret \
  shortener:$(sed -n 's/^version = "\(.*\)"/\1/p' build.gradle.kts)
```

It starts in about 0.4 s. This runs without a profile, so it is quiet and uses the plain defaults. Add
`-e SPRING_PROFILES_ACTIVE=production` to see the production settings.

**The production stack** is rehearsed in [DEPLOY.md](DEPLOY.md#rehearse-it-on-one-machine): a self-signed certificate,
the nginx edge, the migration job and two application tasks, with the same smoke test.

Stop everything with `docker compose down`. Add `-v` to forget the database.

## Try the API

Clients sign in with a signed assertion and get tokens bound to a key (DPoP), so `curl` alone cannot call the API. The
JDK-only client in `deploy/keycloak` does it:

```bash
java deploy/keycloak/DpopClient.java call deploy/keycloak/dev-keys/demo-client.jwk.json demo-client \
  POST http://localhost:8080/api/short-links '{"targetUrl":"https://example.com/some/long/path"}'
```

The response carries the short URL in `Location`. Following it needs no token: `curl -i http://localhost:8080/<code>`.

| Dev client | Scopes | What it is for |
|---|---|---|
| `demo-client` | create, claim, read, delete | the normal case |
| `other-client` | create, read, delete | a second owner: it cannot see `demo-client`'s links (`404`) |
| `admin-client` | admin | reading and disabling any client's links |
| `no-scope-client` | none | being refused (`403`) |

| Command | Does |
|---|---|
| `call KEY CLIENT METHOD URL [BODY]` | gets a token and makes one request |
| `token KEY CLIENT` | prints a token and the key it is bound to |
| `keygen CLIENT` | makes a key pair for a new client |
| `login USER PASSWORD METHOD URL [BODY]` | signs a person in the way a browser app would (`alice` / `alice`, `bob` / `bob`) |

Useful requests, with the client shown after the key file:

| To | Request |
|---|---|
| choose the code | `POST /api/short-links` with `{"targetUrl": "...", "customCode": "my-promo"}` |
| list your links | `GET /api/short-links?page=0&size=20&sort=createdAt,desc` |
| read one | `GET /api/short-links/<code>` |
| disable one | `DELETE /api/short-links/<code>` |
| see every client's | `GET /api/short-links` as `admin-client`, or `?createdBy=<client>` |

`docs/openapi.yaml` is the full contract. The dev keys are public and the dev realm must never be used anywhere real.

## Test it

| Level | Command | Takes | Needs |
|---|---|---|---|
| Everything | `./gradlew test` | about 1 minute | Docker (Testcontainers starts Postgres) |
| One class | `./gradlew test --tests '*ShortLinkAuthorizationTest'` | seconds | |
| The API contract | `./gradlew test --tests '*OpenApiContractTest'` | seconds | fails if `docs/openapi.yaml` drifts from the code |
| The production stack's rules | `./gradlew test --tests '*ComposeStackTest'` | seconds | fails if hardening is loosened |
| The alert rules | `promtool` in a container, see [OBSERVABILITY.md](OBSERVABILITY.md#alerts) | seconds | Docker |
| Every endpoint, on the image | `perf/smoke.sh` | seconds | a running instance and the dev Keycloak |
| Load | `perf/bench.sh`, see below | minutes | Docker, spare cores |

What the tests cover:

- Unit tests of the domain types, the cache, the rate limiter and the services, with an in-memory repository.
- Integration tests against a real Postgres and a stand-in identity provider, for security, DPoP, ownership, errors, the
  database limits and the outage behavior.
- Architecture tests that hold the module and layer boundaries.
- Tests of the files that deploy it: the stack, the profiles, the alert rules and the release version.

**The smoke test** matters for the native image, where something that works on the JVM can fail for want of reflection
metadata. Run it against the container above:

```bash
perf/smoke.sh                                  # http://localhost:8080, management on 8081
```

Against the stack, give it the address, the certificate and the management port; see DEPLOY.md.

**Load tests.** `perf/bench.sh VARIANT IMAGE OUT_DIR` runs one image with 2 cores and 512 MB against Postgres and k6
(`perf/run-all.sh` compares the JVM and native). Stay at or below 5,000 requests a second. Overload runs open thousands
of connections, which an application firewall that inspects new connections (Safing Portmaster, for one) can fail to keep
up with, and then every new connection on the machine fails until reboot. Check `journalctl -k | grep nf_queue` if
connections start timing out.

## Look after it

When it is deployed, with the commands of [DEPLOY.md](DEPLOY.md):

| To | Do |
|---|---|
| see that it is healthy | `docker service ls`; `curl https://<host>/<unknown code>` answers `404` |
| read the logs | `docker service logs shortener_shortener` |
| update | `deploy/stack/deploy.sh <version>`; it migrates first and rolls back a task that does not become healthy |
| roll back | `deploy/stack/deploy.sh <previous version>` |
| scale | `docker service scale shortener_shortener=3`, mindful that limits and the DPoP replay cache are per task |
| take a link down | `DELETE /api/short-links/<code>` as an administrator. Other instances stop serving it within the cache TTL (30 s) |
| add a client | create it in the identity provider with the scopes it needs and the `owner` claim mapper, then give it a key |
| restrict where links may point | set `SHORTENER_SHORTLINK_TARGETURLS_ALLOWEDHOSTS` and deploy |
| look at the database | `perf/pg-diagnostics.sql`, see DEPLOY.md |
| rotate a secret | a new secret under a new name, then deploy; Swarm secrets cannot change |

What to watch is the five alerts in [OBSERVABILITY.md](OBSERVABILITY.md#alerts). The two that mean "the database is in
trouble" are `ShortenerStorageUnavailable` and `ShortenerServingStaleRedirects`. The service keeps redirecting links it
has read, for five minutes, while the database is down, and answers `503` with `Retry-After` for the rest.

## When something is wrong

| You see | It means | Do |
|---|---|---|
| `401` with `error="invalid_token"` | the token is bad, expired, for another audience, or sent as `Bearer` while DPoP is required | send `Authorization: DPoP <token>` with a proof; check the issuer and audience |
| `401` with `error="invalid_dpop_proof"` | the proof does not match the request: method, URL (behind a proxy, the forwarded host and scheme), token or time | make a fresh proof per request; check the edge passes `X-Forwarded-*` |
| `403` with `insufficient_scope` | the token lacks the scope | give the client the scope |
| `404` for a link you know exists | it belongs to another client | use that client, or an administrator |
| `429` | rate limit, per address (300 a minute) or per client (60) | wait `Retry-After`; the limits are per instance |
| `503` with `Retry-After` | no database connection, a statement over 5 s, or a lock over 2 s | check Postgres; `perf/pg-diagnostics.sql` |
| `400` "not accepted by this service" | the target's host is not on the allowlist | add the host, or use another |
| `409` creating a code | the code is taken, or reserved (`api`, `actuator`, `error`) | choose another |
| the app exits at start with `permission denied` | the schema is behind and the application role cannot change it | run the migration job first |
| native build ends with exit 137 | the machine ran out of memory | close other work; it needs about 7 GB free |
| `docker ps` shows nothing you started | Docker Desktop switched the CLI to its own daemon | `DOCKER_CONTEXT=default`, and `DOCKER_HOST=unix:///var/run/docker.sock` for Gradle |
| `The configuration of the pool is sealed` | an environment variable was spelled with a separator inside a word | `..._CONNECTIONTIMEOUT`, not `..._CONNECTION_TIMEOUT` |
