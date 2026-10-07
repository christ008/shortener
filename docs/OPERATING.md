# Operating and testing it

Run the service locally, call it, test it, and look after it. Production setup is in [DEPLOY.md](DEPLOY.md), what to watch
is in [OBSERVABILITY.md](OBSERVABILITY.md), the design is in [INTERNALS.md](INTERNALS.md).

- [Requirements](#requirements)
- [Run it](#run-it)
- [Call the API](#call-the-api)
- [Test it](#test-it)
- [Look after it](#look-after-it)
- [Troubleshooting](#troubleshooting)
- [Scripts](#scripts)

## Requirements

| For | You need |
|---|---|
| Running and testing | Docker, JDK 25 (Gradle finds one, or use SDKMAN) |
| `dev-setup`, `smoke`, `report` and the other [tools](#scripts) | a JDK 17 or newer (a JRE runs them only once built), and the Gradle wrapper (builds them the first time) |
| The native image | about 7 GB free memory, 3 minutes |
| The load test | Docker (k6 runs in a container), spare cores |
| The production stack on one machine | `openssl`, `keytool` |

## Run it

| | Command | Use it for |
|---|---|---|
| JVM | `./gradlew bootRun` | day to day, the `dev` profile |
| Native image | `./gradlew bootBuildImage`, then `docker run` | what production runs |
| Production stack | `deploy/stack/local/prepare.sh`, then deploy | TLS, edge, migration job, rolling updates |

**JVM.** Run `deploy/keycloak/dev-setup` once, then `bootRun`. It starts Postgres and Keycloak from `compose.yaml` and
applies the migrations. Ports: app `8080`, management (health, metrics) `8081`, Keycloak `8180`. The `dev` profile samples
every trace, shows health details and relaxes the rate limits.

**Native image.** Postgres and Keycloak still come from compose:

```bash
./gradlew bootBuildImage
docker compose up -d postgres keycloak
port=$(docker compose port postgres 5432 | cut -d: -f2)
docker run --rm --network host --memory 512m \
  -e SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:$port/mydatabase \
  -e SPRING_DATASOURCE_USERNAME=myuser -e "SPRING_DATASOURCE_PASSWORD=$(deploy/keycloak/dev-setup --show | sed -n 's/^postgres *myuser \/ //p')" \
  shortener:$(sed -n 's/^version = "\(.*\)"/\1/p' build.gradle.kts)
```

It runs without a profile. Add `-e SPRING_PROFILES_ACTIVE=production` for the production settings.

> [!NOTE]
> `mydatabase` and `myuser` are the development database and its superuser, from `compose.yaml`. Connecting as that user
> bypasses the role split of [ADR 0013](adr/0013-three-database-roles-and-a-migration-job.md): the stack connects as
> `shortener_app`, which cannot delete a link, change a target or alter the schema.

**Production stack.** See [DEPLOY.md](DEPLOY.md#rehearse-it-on-one-machine).

Stop everything with `docker compose down`; add `-v` to drop the database.

## Call the API

### dev-setup

Run `deploy/keycloak/dev-setup` once. It asks for the values below, offering a random one for each:

| Asks for | Used by |
|---|---|
| where client keys go (default `deploy/keycloak/dev-keys`) | the client, `perf/smoke.sh`, the load test |
| Keycloak console user and password | Keycloak at <http://localhost:8180> |
| passwords of web users `alice` and `bob` | the client's `login` |
| Postgres password, and those of `shortener_app`, `shortener_migrator`, `shortener_exporter` | `compose.yaml` |

It writes a private key for each of the four dev clients, the dev realm (`deploy/keycloak/shortener-realm.json`, from
`shortener-realm.template.json`) and the passwords to `.env`. Git ignores all of it.

| Option | Effect |
|---|---|
| `--yes` | take every default, ask nothing |
| `--force` | replace an existing setup |
| `--show` | print the passwords |

After a new setup run `docker compose down -v`: Postgres and Keycloak keep the passwords they first started with.

### DPoP client

`curl` cannot call the API: tokens are bound to a key and every request needs a proof. `deploy/keycloak/DpopClient.java` is a
**reference implementation** of a client, to try the API with, to read and to port: it needs only a JDK 17 or newer, runs on the
caller's machine (it must reach the token endpoint and the API) and is not deployed. It is not a library, and a program that calls
the API should use one of the [libraries below](#other-clients-and-libraries):

```bash
java deploy/keycloak/DpopClient.java call deploy/keycloak/dev-keys/demo-client.jwk.json demo-client \
  POST http://localhost:8080/api/short-links '{"targetUrl":"https://example.com/some/long/path"}'
```

| Command | Does |
|---|---|
| `call KEY CLIENT METHOD URL [BODY]` | gets a token, makes one request |
| `token KEY CLIENT` | prints a token and the key it is bound to |
| `keygen CLIENT` | prints a key pair: line 1 private JWK, line 2 public JWK |
| `login USER PASSWORD METHOD URL [BODY]` | signs a person in as a browser app would (`alice` or `bob`) |

| Setting | Effect |
|---|---|
| `TOKEN_URL`, `ISSUER` | another identity provider (`ISSUER` defaults to `TOKEN_URL` without `/protocol/openid-connect/token`) |
| `TRACEPARENT=00-<trace id>-<span id>-01` | force a sampled trace |
| `DPOP_DEBUG=1` | print the stack trace of an error |
| `-Djavax.net.ssl.trustStore=ts.p12 -Djavax.net.ssl.trustStorePassword=...` (or `JAVA_TOOL_OPTIONS`) | trust a certificate of your own |

Exit status: `0` when it did what was asked (an error status from the API is the answer, so it counts), `1` when it
could not (one line on stderr), `2` for bad arguments.

It follows the nonce protocol of [ADR 0032](adr/0032-dpop-nonces.md): the first request of a call is answered `401` with
`error="use_dpop_nonce"` and a `DPoP-Nonce` header, and it repeats the request once with a new proof that carries the nonce. An
identity provider that asks the same (`400` with that error) is answered the same way, with a new client assertion.

#### Other clients and libraries

A program of your own should use a maintained DPoP library for its language. These were found and checked for DPoP support when this
was written; they are pointers, not endorsements, so look at how each is maintained and whether it handles nonces:

| Language | Library | Notes |
|---|---|---|
| JavaScript, TypeScript (Node, browsers, Deno, Bun) | [dpop](https://github.com/panva/dpop) | makes proofs, with the nonce of the authorization server and of the resource server |
| | [oauth4webapi](https://github.com/panva/oauth4webapi) | an OAuth and OpenID Connect client that lists DPoP among its features |
| Java | [Nimbus OAuth 2.0 SDK](https://connect2id.com/products/nimbus-oauth-openid-connect-sdk/examples/oauth/dpop) | DPoP proofs and sender-constrained tokens |
| .NET | [Duende](https://duendesoftware.com/blog/20230504-dpop) | DPoP in its client libraries and in the Microsoft OpenID Connect handler |
| Go | [go-dpop](https://pkg.go.dev/github.com/AxisCommunications/go-dpop) | proof generation and validation |
| | [conductorone/dpop](https://pkg.go.dev/github.com/conductorone/dpop) | proofs, a `net/http` client and server middleware |
| Dart, Flutter | [dpop](https://pub.dev/packages/dpop) | proofs, signing and nonce retries |
| Python | none checked | |

Whatever you use, it must:

1. Make a key for each token and ask the token endpoint for a token bound to it.
2. Send `Authorization: DPoP <token>` and, with each request, a `DPoP` proof for it: method (`htm`), URL without the query
   (`htu`), hash of the token (`ath`), a new `jti` and the time.
3. Keep the latest `DPoP-Nonce` of each server, put it in the `nonce` claim, and repeat a request once, with a new proof, when
   it is answered `401` with `error="use_dpop_nonce"`.

The specification is [RFC 9449](https://www.rfc-editor.org/rfc/rfc9449.html). `DpopClient.java` does all three in one file, and so
does `DpopCalls` in `tools/src/main/kotlin`, which `Smoke` uses.

| Dev client | Scopes | Is for |
|---|---|---|
| `demo-client` | create, claim, read, delete | the normal case |
| `other-client` | create, read, delete | a second owner: cannot see `demo-client`'s links (`404`) |
| `admin-client` | admin | reading and disabling any client's links |
| `no-scope-client` | none | being refused (`403`) |

| To | Request |
|---|---|
| make a link | `POST /api/short-links` with `{"targetUrl": "..."}`, which picks the code |
| choose the code | `PUT /api/short-links/my-promo` with `{"targetUrl": "..."}`, which needs the `claim` scope |
| list your links | `GET /api/short-links?page=0&size=20&sort=createdAt,desc` |
| read one | `GET /api/short-links/<code>` |
| disable one | `PATCH /api/short-links/<code>` with `{"disabled": true}` |
| list every client's | `GET /api/short-links` as `admin-client`, or `?createdBy=<client>` |

Contract: [openapi.yaml](openapi.yaml).

### Lost answers

A timeout or a reset leaves a client not knowing whether the service served the request. What to do depends on the request:

| You sent | Send it again? | If the answer is still missing or is a `409` |
|---|---|---|
| `GET` | yes | |
| `PATCH` with `{"disabled": true}` | yes, it is idempotent: the answer is the link as it was first disabled | |
| `PUT /api/short-links/<code>` | yes. `201` if the first one never arrived, `200` with the link if it did | `409` is another client's code, a reserved one, or yours for another target. `GET /api/short-links/<code>`, which needs `read`, tells which: `200` is yours (compare `targetUrl`), `404` is not |
| `POST /api/short-links` | **not blindly**: every `POST` makes another link, with another code | look first, see below |

For a `POST` whose answer was lost:

1. `GET /api/short-links?size=20&sort=createdAt,desc` lists your links, newest first. A link with your `targetUrl` made after
   you sent the request is the one you lost.
2. If it is there, use it. If it is not, send the `POST` again.
3. This cannot tell your links apart when you make several to the same target, and a page is only as far back as you look.
4. If duplicates matter and the client has the `claim` scope, make the code yourself (a random one of 12 or more letters and
   digits) and `PUT` it. Then sending it again is always safe, and none of this is needed.

The service keeps no record of requests, so there is nothing else to ask it. [ADR 0034](adr/0034-claim-a-chosen-code-with-put.md)
says why.

## Test it

| Level | Command | Needs |
|---|---|---|
| Everything (about 1 minute) | `./gradlew test` | Docker (Testcontainers starts Postgres) |
| One class | `./gradlew test --tests '*ShortLinkAuthorizationTest'` | |
| API contract | `./gradlew test --tests '*OpenApiContractTest'` | |
| Stack hardening | `./gradlew test --tests '*ComposeStackTest'` | |
| Alert rules | `promtool` in a container, see [OBSERVABILITY.md](OBSERVABILITY.md#alerts) | Docker |
| Every endpoint, on the image | `perf/smoke.sh` | a running instance, the dev Keycloak |
| Load | `perf/bench.sh` | Docker, spare cores |

The suite has unit tests (in-memory repository), integration tests (real Postgres, stand-in identity provider),
architecture tests, and tests of the deployment files (stack, profiles, alert rules, release version).

**Smoke test.** Run it against the native image:

```bash
perf/smoke.sh                   # http://localhost:8080, management on 8081
```

Against the stack: give it the address, the certificate and the management port ([DEPLOY.md](DEPLOY.md#rehearse-it-on-one-machine)).

**Load test.** `perf/bench.sh VARIANT IMAGE OUT_DIR` runs one image with 2 cores and 512 MB against Postgres and k6.
`perf/run-all.sh` compares the JVM and the native image.

> Stay at or below 5,000 requests a second. Overload runs can take down the network of a machine with an application
> firewall that inspects new connections. See [INTERNALS.md](INTERNALS.md#running-load-tests-safely).

## Look after it

Commands of [DEPLOY.md](DEPLOY.md), on a deployed stack:

| To | Do |
|---|---|
| check health | `docker service ls`; `curl https://<host>/<unknown code>` answers `404` |
| read logs | `docker service logs shortener_shortener` |
| update | `deploy/stack/deploy.sh <version>` (checks the image's signature first) |
| roll back | `deploy/stack/deploy.sh <previous version>` |
| scale | `docker service scale shortener_shortener=3` (limits and the DPoP replay cache are per task) |
| take a link down | `PATCH /api/short-links/<code>` with `{"disabled": true}` as an administrator (other instances stop within 30 s, or within 5 minutes on one that cannot reach the database) |
| add a client | create it in the identity provider with its scopes and the `owner` claim mapper, then give it a key |
| restrict target hosts | set `ALLOWED_TARGET_HOSTS` in `.env`, deploy |
| inspect the database | `perf/pg-diagnostics.sql` ([DEPLOY.md](DEPLOY.md#operate)) |
| rotate a secret | create it under a new name, deploy |

Alerts are listed in [OBSERVABILITY.md](OBSERVABILITY.md#alerts). `ShortenerStorageUnavailable` and
`ShortenerServingStaleRedirects` mean the database is in trouble: the service keeps redirecting links it has read for five
minutes, and answers `503` with `Retry-After` for the rest.

## Troubleshooting

| You see | Cause | Do |
|---|---|---|
| `401`, `error="invalid_token"` | bad or expired token, wrong audience, or `Bearer` while DPoP is required | send `Authorization: DPoP <token>` with a proof; check issuer and audience |
| `401`, `error="invalid_dpop_proof"` | proof does not match the request: method, URL (including the port), token or time | make a fresh proof per request; behind a proxy, check `X-Forwarded-Host`, `-Proto` and `-Port` |
| `403`, `insufficient_scope` | token lacks the scope | give the client the scope |
| `404` for a link that exists | it belongs to another client | use that client, or an administrator |
| `429` | rate limit: 300 a minute per address, 60 per client | wait `Retry-After`; limits are per instance. Everyone behind one NAT shares the address limit |
| `503` with `Retry-After` | no database connection, a statement over 5 s, or a lock over 2 s | check Postgres, `perf/pg-diagnostics.sql` |
| `400` "not accepted by this service" | target host not on the allowlist | add the host |
| `409` claiming a code | code reserved (`api`, `actuator`, `error`, `app`), taken by another client, or yours for another target | choose another; see [lost answers](#lost-answers) to tell yours from theirs |
| app exits at start with `permission denied` | schema behind, and the application role cannot change it | run the migration job first |
| native build exits with 137 | out of memory | free about 7 GB |
| `docker ps` shows nothing you started | Docker Desktop switched the CLI to its own daemon | `DOCKER_CONTEXT=default`; for Gradle `DOCKER_HOST=unix:///var/run/docker.sock` |
| `The configuration of the pool is sealed` | environment variable spelled with a separator inside a word | `..._CONNECTIONTIMEOUT`, not `..._CONNECTION_TIMEOUT` |

## Scripts

Scripts that start programs are POSIX `sh`, checked with `shellcheck --shell=sh`. Scripts that compute are Kotlin tools in
`tools/`, built by `tools/run` the first time and when a source changes (JDK 17 or newer). The one exception is
`DpopClient.java`, a single Java file for JDK 17 or newer with no build, which the tools do not use. See
[ADR 0026](adr/0026-scripting-standard.md), [0029](adr/0029-tools-in-kotlin.md) and [0020](adr/0020-dpop-client-in-java.md).

| Script | Is for | Kind |
|---|---|---|
| `deploy/stack/deploy.sh` | deploy or update the stack on a manager | sh |
| `deploy/stack/local/prepare.sh` | throwaway secrets and certificate for a rehearsal | sh |
| `deploy/postgres/bootstrap.sql`, `set-role-passwords.sh`, `keycloak-database.sh`, `include-diagnostics.sh` | roles and databases at first Postgres start | sql, sh |
| `deploy/postgres/backup` | back up, check and restore-test the stack's Postgres, from a manager | sh |
| `deploy/keycloak/entrypoint.sh` | read Keycloak's secrets from files, start it | sh |
| `deploy/keycloak/dev-setup`, `perf/smoke.sh` | start `DevSetup` and `Smoke` | sh |
| `tools/run TOOL [ARGS]` | build the tools if needed, start one | sh |
| `DevSetup` | dev keys, realm, passwords, `.env` | Kotlin |
| `Realms` | realm from a template and public keys | Kotlin |
| `Smoke` | check every endpoint of a running instance | Kotlin |
| `Report` | read GC logs, heap dumps and bench results; query Prometheus | Kotlin |
| `ClientKeys`, `DpopCalls` | client keys, and DPoP sign-in and calls, for `DevSetup` and `Smoke` | Kotlin |
| `deploy/keycloak/DpopClient.java` | sign in and call the API with DPoP; make client keys | Java |
| `perf/bench.sh`, `run-all.sh`, `profile.sh`, `tune-connections.sh` | run the load test, profile | sh |
| `perf/k6/mixed.js` | the load workload | k6 |

Gradle tasks (`./gradlew tasks --group tooling`):

| Task | Runs | Settings |
|---|---|---|
| `devSetup`, `devPasswords` | `dev-setup --yes` (`--force` with `-Pforce`), `--show` | |
| `keygen` | `DpopClient.java keygen` | `-Pclient=NAME` |
| `productionRealm` | `tools/run Realms production` | `-Pdemo=FILE -Padmin=FILE -Poutput=FILE` |
| `dpopCall` | `DpopClient.java call` | `-Pkey=FILE -Pclient=NAME -Purl=URL -Pmethod -Pbody` |
| `smoke` | `tools/run Smoke` | `-PbaseUrl=URL -Pmgmt=URL` |
| `stackPrepare` | `deploy/stack/local/prepare.sh` | |
| `postgresBackup` | `deploy/postgres/backup` | `-Pcommand=init\|full\|diff\|check\|info\|restore-test` |
| `report` | `tools/run Report` | `-Preport=summary\|gc\|hprof\|profile\|json -Ptarget=PATH -Ptop=N` |

The tasks run with the project's JDK 25 toolchain. `devSetup` takes the defaults because Gradle has no terminal: run the
script to be asked. `tools/run` works by hand with any JDK 17 or newer. Tests: `./gradlew test` (with the application's) or
`./gradlew -p tools test`.
