# Operating

Run the service in development, rehearse the production stack on one machine, and go to production, in that order. Then the
configuration reference and the day-to-day. What to watch is in [OBSERVABILITY.md](OBSERVABILITY.md), the production runbook
(Keycloak, backups, other hosts) is [DEPLOY.md](DEPLOY.md), how it works is in [INTERNALS.md](INTERNALS.md), its design and invariants
are in [DESIGN.md](DESIGN.md) and its security controls are in [SECURITY.md](SECURITY.md).

1. [Develop](#develop): set up, run, call, test.
2. [Rehearse production on one machine](#rehearse-production-on-one-machine): the real stack, with throwaway secrets.
3. [Go to production](#go-to-production): a checklist.

Reference:

- [Requirements](#requirements)
- [Configuration](#configuration)
- [Call the API](#call-the-api)
- [Test it](#test-it)
- [Look after it](#look-after-it)
- [Troubleshooting](#troubleshooting)
- [Scripts](#scripts)

## Requirements

| For | You need |
|---|---|
| Running and testing | Docker, JDK 25 (Gradle finds one, or use SDKMAN) |
| `dev-setup`, `smoke`, `report` and the other [tools](#scripts) | a JDK 25 (a JRE runs them only once built), and the Gradle wrapper (builds them the first time) |
| The native image | about 7 GB free memory, 3 minutes |
| The load test | Docker (k6 runs in a container), spare cores, `jq` |
| The production stack on one machine | `openssl`, `keytool` |

## Develop

You finish with the service running on your machine, one call made and the tests passing.

1. **Set up, once.** `deploy/keycloak/dev-setup` generates what a local run needs and nothing else. It asks for the values below,
   offering a random one for each:

   | Asks for | Used by |
   |---|---|
   | where client keys go (default `deploy/keycloak/dev-keys`) | the client, `perf/smoke.sh`, the load test |
   | Keycloak console user and password | Keycloak at <http://localhost:8180> |
   | passwords of web users `alice` and `bob` | the client's `login` |
   | Postgres password, and those of `shortener_app`, `shortener_migrator`, `shortener_exporter` | `compose.yaml` |

   It writes a private key for each of the four dev clients, the dev realm (`deploy/keycloak/shortener-realm.json`, from
   `shortener-realm.template.json`) and the passwords to `.env`. Git ignores all of it, and none of it is ever shared: every
   machine makes its own.

   | Option | Effect |
   |---|---|
   | `--yes` | take every default, ask nothing |
   | `--force` | replace an existing setup |
   | `--show` | print the passwords |

   After a new setup run `docker compose down -v`: Postgres and Keycloak keep the passwords they first started with.
2. **Run it.** `./gradlew bootRun` starts Postgres and Keycloak from `compose.yaml`, applies the migrations and starts the
   application under the `dev` profile. Ports: app `8080`, management (health, metrics) `8081`, Keycloak `8180`.
3. **Call it.** DPoP is on in development too, so `curl` alone cannot call the API; use the reference client
   ([how it works](#dpop-client)):

   ```bash
   java deploy/keycloak/DpopClient.java call deploy/keycloak/dev-keys/demo-client.jwk.json demo-client \
     POST http://localhost:8080/api/short-links '{"targetUrl":"https://example.com/some/long/path"}'
   curl -i http://localhost:8080/<shortCode>
   ```
4. **Test it.** `./gradlew test` takes about a minute and needs Docker (Testcontainers starts Postgres). Other levels,
   the smoke test and the load test are in [Test it](#test-it).
5. **Optional: run the image you ship.** The native image takes about 7 GB free and 3 minutes to build. Postgres and Keycloak
   still come from compose:

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

6. **Stop.** `docker compose down`; add `-v` to drop the database.

What the `dev` profile changes: debug logs for this application, health details, every trace sampled, and rate limits high
enough not to get in the way ([the profiles](#the-profiles)). What development does not exercise: `bootRun` connects as the
superuser `myuser`, so the three database roles and everything that follows from them are not in play until the
[rehearsal](#rehearse-production-on-one-machine).

## Rehearse production on one machine

Runs the production stack, `deploy/stack/compose.prod.yaml`, on your machine: the nginx edge with TLS, the application, the
migration job and Postgres, started by the same `deploy/stack/deploy.sh` that deploys for real. Throwaway secrets, a self-signed
certificate and the dev-realm Keycloak stand in for the real ones. Do it before the first deploy and after changing a stack
file. It needs Docker 25 or later, `openssl` and `keytool`, plus the room to build the image.

1. **Build the image** (or skip this and use a published one by setting `SHORTENER_IMAGE` in `.env`):

   ```bash
   ./gradlew bootBuildImage
   version=$(sed -n 's/^version = "\(.*\)"/\1/p' build.gradle.kts)
   ```
2. **Prepare.** `deploy/stack/local/prepare.sh` writes what the stack reads, and refuses to overwrite an existing `secrets/`:

   | It writes | Content |
   |---|---|
   | `secrets/` | random database passwords and nonce secret, and a certificate for `localhost` valid 30 days, all mode `0444` |
   | `.env` | `SHORTENER_IMAGE=shortener`, `VERIFY_SIGNATURE=never`, the versions, and `ISSUER_URI` and `JWKS_URI` of the dev Keycloak. What is already there is kept |
   | dev setup | runs `dev-setup --yes` first if the dev realm or its Keycloak password is missing |
3. **Make this machine a one-node Swarm.** `--listen-addr` keeps the manager off the network:

   ```bash
   docker swarm init --advertise-addr 127.0.0.1 --listen-addr 127.0.0.1:2377
   docker node update --label-add shortener.postgres=true "$(docker node ls -q)"
   ```
4. **Deploy.** `compose.local.yaml` adds the dev Keycloak, one application task, the management port on the host and
   `ALLOWANY=true` for the target hosts. `RESOLVE_IMAGE=never` uses the image you built:

   ```bash
   COMPOSE_FILES="deploy/stack/compose.prod.yaml deploy/stack/local/compose.local.yaml" RESOLVE_IMAGE=never \
     deploy/stack/deploy.sh "$version"
   ```
5. **Look.** Everything starts at once and the application restarts until the migration job ends (seconds):

   ```bash
   docker service ls                                                  # shortener_shortener 1/1
   curl -ks -o /dev/null -w '%{http_code}\n' https://localhost/nosuchcode   # 404
   docker service logs shortener_shortener 2>&1 | grep 'Security configured'
   ```
6. **Smoke test** every endpoint, trusting the certificate:

   ```bash
   keytool -importcert -noprompt -alias local -file secrets/tls_cert -keystore ts.p12 -storetype PKCS12 -storepass changeit
   JAVA_TOOL_OPTIONS="-Djavax.net.ssl.trustStore=$PWD/ts.p12 -Djavax.net.ssl.trustStorePassword=changeit" \
     MGMT=http://localhost:8081 perf/smoke.sh https://localhost
   ```
7. **Change a setting and deploy again.** Put `RATE_LIMIT_PER_CLIENT=5` in `.env`, run step 4 again and read the `Security
   configured` line: it says 5. That is the path every [tunable](#tunables) takes, and an update is the same command with a new
   version.
8. **Remove it:** `docker stack rm shortener`, then `docker swarm leave --force`.

| The rehearsal shows | It cannot show |
|---|---|
| the stack files are valid, the secrets are read from files, the migration job runs and the application then starts | your identity provider: the dev realm and its Keycloak stand in |
| the edge terminates TLS and forwards the client's address and port | a real certificate, DNS or a public address |
| the application connects as `shortener_app` and the job as `shortener_migrator` | the image signature: `VERIFY_SIGNATURE=never` |
| an update and a configuration change reach the container | the target-host policy: `ALLOWANY=true`. Set `ALLOWED_TARGET_HOSTS` to see the real one |
| | more than one node: encrypted networks, placement, a second task (the management port is published in host mode, so a second task cannot start on this node) |

## Go to production

Work down the list. Each item names the invariant it protects ([Invariants](DESIGN.md#invariants)), and the how is in
[DEPLOY.md](DEPLOY.md) and [Configuration](#configuration).

**Before the first deploy**

- [ ] The [rehearsal](#rehearse-production-on-one-machine) passed on the version you are about to deploy.
- [ ] You have an identity provider of your own: its tokens carry the `owner` claim and bind to the client's key (DPoP), and the
  audience is `shortener-api`. Never import the dev realm into it. Your own Keycloak: [DEPLOY.md](DEPLOY.md#keycloak).
  *(Security 2 and 3)*
- [ ] `ISSUER_URI` and `JWKS_URI` in `.env` point at it.
- [ ] The target policy is decided: `ALLOWED_TARGET_HOSTS` lists the hosts links may point to. `ALLOW_ANY_TARGET=true` is for a
  private instance of trusted clients only. The application does not start with neither. *(Security 1)*
- [ ] Secrets are made on the host with `openssl rand`, not copied from the rehearsal or from anywhere in the repository. `secrets/`
  is outside Git, every file is mode `0444`, and `dpop_nonce_secret` is the same for every instance. *(Security 4 and 5)*
- [ ] A certificate and key for the host name are in `secrets/tls_cert` and `tls_key`, and DNS points at the node that runs the edge.
- [ ] `cosign` is on the manager and `VERIFY_SIGNATURE` is not `never`. The version is a published release.
- [ ] Nobody has set `SPRING_PROFILES_ACTIVE`, or `SHORTENER_SECURITY_DPOP_REQUIRED`, outside the stack file. Production refuses to
  start with DPoP off. *(Operational 1)*
- [ ] `tasks x 10` is below Postgres's `max_connections`.
- [ ] The private key of `admin-client` stays on your machine, encrypted, never on the VM.
- [ ] A managed database: `deploy/postgres/bootstrap.sql` has been run, the role passwords match the secret files, and both URLs
  use `sslmode=verify-full` ([DEPLOY.md](DEPLOY.md#managed-database)). *(Short-code 5)*

**Deploy**

- [ ] `deploy/stack/deploy.sh <version>`. The migration job finishes before the application is updated. *(Operational 2)*
- [ ] `docker service ls` shows every task up, and `curl https://<host>/nosuchcode` answers `404`.
- [ ] `docker service logs shortener_shortener | grep 'Security configured'` says `dpop.required=true`.
- [ ] `perf/smoke.sh https://<host>`, if you can sign in as the dev clients; otherwise one create, one redirect and one disable
  with a client of yours.

**After**

- [ ] Backups: the [overlay](DEPLOY.md#backups-and-a-replica) is on, `deploy/postgres/backup init` ran, cron runs `full` and `diff`,
  and `restore-test` was run once and compared with the database.
- [ ] Alerts: the observability overlay is on and an Alertmanager is wired in ([OBSERVABILITY.md](OBSERVABILITY.md#alerts)).
- [ ] Rolling back works: you ran `deploy/stack/deploy.sh <previous version>` once.
- [ ] A takedown works: an administrator disables a link and every instance answers `410` within 30 s. *(Short-code 2 and 4)*
- [ ] A public instance: [CLOUD.md](CLOUD.md) lists what is still missing.

## Configuration

Three layers, each overriding the one before:

```
application.yaml                  defaults, in the image                    the same everywhere
application-<profile>.yaml        dev, or production                        chosen by SPRING_PROFILES_ACTIVE
environment variables + secrets   set for one deployment                    .env, secret files, the stack file
```

Where a value belongs: if it is secret, it is a file under `secrets/`, never a variable. If it differs between deployments, it is
a variable in `.env`. If it differs between development and production, it is in the profile. Anything else is a default.

### The profiles

`dev` is set by `./gradlew bootRun` and `production` by the stack file. With no profile you get the defaults.

| Setting | Default | `dev` | `production` |
|---|---|---|---|
| Logs | plain text | plain, `DEBUG` for this application | JSON (ECS) |
| Health details, error messages | hidden | health details shown | hidden, no stack traces or messages |
| Trace sampling | none | every trace | 5% |
| Rate limit per IP, per client (a minute) | 300, 60 | 10,000, 5,000 | 300, 60 |
| DPoP | required | required | required, and **the application does not start with it off** |
| Target hosts | every host | every host | **must be decided, or the application does not start** |
| DPoP nonce secret | random for each process | random for each process | **required**, shared by every instance |

### `.env`: the stack's variables

One file at the repository root, ignored by Git, read by `deploy/stack/deploy.sh` and by Compose
(`docker compose --env-file .env -f deploy/stack/compose.prod.yaml ...`, because Compose looks for `.env` next to the compose file).
`deploy/stack/.env.example` lists every variable, and `ComposeStackTest` fails when it and the stack files disagree. The rehearsal's
`prepare.sh` and development's `dev-setup` write to the same file; they do not clash.

What to deploy:

| Variable | Default | Meaning |
|---|---|---|
| `SHORTENER_IMAGE` | `ghcr.io/christ008/shortener` | the image. Only `ghcr.io` images pass the signature check. The rehearsal sets `shortener` |
| `SHORTENER_VERSION`, `SHORTENER_MIGRATE_VERSION` | none, required | the tags of the application and the migration job. `deploy.sh VERSION` sets both and wins over `.env`; the file matters for plain `docker compose` |

Passed to the `shortener` service, and to the migration job when noted:

| Variable | Default | Meaning | Becomes |
|---|---|---|---|
| `ISSUER_URI` | none, required | the identity provider's issuer. Also the job | `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUERURI` |
| `JWKS_URI` | none, required | where it publishes its keys, as the stack reaches them. Also the job | `..._JWT_JWKSETURI` |
| `ALLOWED_TARGET_HOSTS` | empty | comma-separated hosts links may point to: `example.com`, `*.example.org` (subdomains only) | `SHORTENER_SHORTLINK_TARGETURLS_ALLOWEDHOSTS` |
| `ALLOW_ANY_TARGET` | `false` | `true` accepts every host. Set one of the two, not both | `..._TARGETURLS_ALLOWANY` |
| `SPRING_DATASOURCE_URL` | the stack's `postgres` | the database, as `shortener_app`. Also the job | the same |
| `SPRING_FLYWAY_URL` | the stack's `postgres` | the database, as `shortener_migrator`. The job only | the same |
| `OTLP_ENDPOINT` | `http://localhost:4318/v1/traces` | where traces go (OTLP over HTTP) | `MANAGEMENT_OPENTELEMETRY_TRACING_EXPORT_OTLP_ENDPOINT` |

The database users are fixed in the stack file, and their passwords are [secrets](#secret-files). The audience (`shortener-api`)
is a default of the image, not a variable.

#### Tunables

Optional, shown with the defaults they have when unset. Each reaches the `shortener` service only.

| Variable | Default | Meaning | Becomes |
|---|---|---|---|
| `RATE_LIMIT_PER_IP` | `300` | requests a minute per address, per instance. Everyone behind one NAT shares it | `SHORTENER_SECURITY_RATELIMIT_PERIP_CAPACITY` |
| `RATE_LIMIT_PER_CLIENT` | `60` | requests a minute per authenticated client, per instance | `..._RATELIMIT_PERCLIENT_CAPACITY` |
| `MAX_CONNECTIONS` | `500` | connections accepted before refusing, about 150 KB of heap each | `SERVER_TOMCAT_MAXCONNECTIONS` |
| `REDIRECT_CACHE_ENABLED` | `true` | `false` makes every redirect read the database | `SHORTENER_SHORTLINK_REDIRECTCACHE_ENABLED` |
| `REDIRECT_CACHE_TTL` | `30s` | how long an entry lives, and so how long another instance follows a link after it was disabled | `..._REDIRECTCACHE_TTL` |
| `REDIRECT_CACHE_MAX_ENTRIES` | `100000` | the entry cap | `..._REDIRECTCACHE_MAXENTRIES` |
| `REDIRECT_CACHE_STALE_IF_ERROR` | `5m` | how long a link already read is still followed while the database is unreachable. `0` turns it off, and it may not be shorter than the TTL | `..._REDIRECTCACHE_STALEIFERROR` |

Not exposed on purpose: `SHORTENER_SECURITY_DPOP_REQUIRED`. Production refuses to start with it off.

To expose another property, add `NAME: '${VARIABLE:-default}'` under `environment:` of `shortener` in
`deploy/stack/compose.prod.yaml`, and the variable to `.env.example`. The container sees only what that list names, so a variable
set in `.env` and not listed there changes nothing. Write the name with no separator inside a word:
`SPRING_DATASOURCE_HIKARI_CONNECTIONTIMEOUT`, not `..._CONNECTION_TIMEOUT`, which fails at start with `The configuration of
the pool is sealed once started`.

#### Optional services

| Variable | Default | Meaning |
|---|---|---|
| `PUBLIC_URL` | none, required with the Keycloak overlay | the public base URL, such as `https://shortener.example.com`. It is Keycloak's host name, and `ISSUER_URI` is `PUBLIC_URL/realms/shortener` |
| `KEYCLOAK_IMAGE`, `KEYCLOAK_VERSION` | `shortener-keycloak`, `26.8.0` | the Keycloak image built from `deploy/keycloak` |
| `POSTGRES_IMAGE`, `POSTGRES_VERSION` | `shortener-postgres`, `18.6` | the Postgres image built from `deploy/postgres`, with the backups and replica overlay |
| `SECRETS_DIR` | `secrets/` at the repository root | where the [secret files](#secret-files) are. Give an absolute path: a relative one is read from `deploy/stack` |

Switches of `deploy/stack/deploy.sh`, which may also be in `.env`:

| Variable | Effect |
|---|---|
| `OBSERVABILITY=1`, `KEYCLOAK=1`, `POSTGRES_HA=1` | add the overlay in `deploy/stack/overlays/` |
| `COMPOSE_FILES` | the files to deploy, default `deploy/stack/compose.prod.yaml`. The rehearsal adds `deploy/stack/local/compose.local.yaml` |
| `RESOLVE_IMAGE` | `always` (default) asks the registry for the digest, `never` uses what the node has |
| `VERIFY_SIGNATURE` | `always` (default) runs `cosign verify` first, `never` skips it (the rehearsal) |
| `WAIT` | seconds to wait for the migration job, default 300 |

### Secret files

One file each under `secrets/`, mode `0444` (Swarm mounts a secret with the file's mode, and the containers run as other users).
They are never variables, and a secret is immutable: rotate it under a new name in the stack file and deploy. The application
reads them through `SPRING_CONFIG_IMPORT=configtree:/run/secrets/`, where each file is a property named like the file.

| File | Read by, as | Needed |
|---|---|---|
| `tls_cert`, `tls_key` | the edge | always |
| `db_postgres_password` | Postgres, once, to create the roles | unless the database is managed |
| `db_app_password` | the application and the job, as `spring.datasource.password`; Postgres sets the role's password from it | always |
| `db_migrator_password` | the job only, as `spring.flyway.password`; Postgres sets the role's password from it | always |
| `db_exporter_password` | Postgres, for the statistics role, and the exporter of the observability overlay | always |
| `dpop_nonce_secret` | the application only, as `shortener.security.dpop.nonce.secret`. The same for every instance | always: production does not start without it |
| `db_keycloak_password`, `keycloak_admin_password` | the Keycloak overlay | with `KEYCLOAK=1` |
| `db_replicator_password` | the backups and replica overlay | with `POSTGRES_HA=1` |

`deploy/stack/local/prepare.sh` makes throwaway ones for the rehearsal. [DEPLOY.md](DEPLOY.md#first-deploy) makes real ones.

### Development

`dev-setup` writes these to `.env`. Only `compose.yaml` reads them; the application does not. `bootRun` gets its database
credentials from Compose through Spring Boot's Docker Compose support, and reaches Keycloak at the `localhost:8180` default of
`application.yaml`.

| Variable | Is |
|---|---|
| `DEV_KEYS_DIR` | where the client keys go |
| `DEV_KEYCLOAK_ADMIN_USER`, `DEV_KEYCLOAK_ADMIN_PASSWORD` | the Keycloak console at <http://localhost:8180> |
| `DEV_POSTGRES_PASSWORD` | the superuser `myuser` of the development database |
| `DEV_APP_PASSWORD`, `DEV_MIGRATOR_PASSWORD`, `DEV_EXPORTER_PASSWORD` | the passwords of `shortener_app`, `shortener_migrator` and `shortener_exporter` |
| `DEV_USER_ALICE_PASSWORD`, `DEV_USER_BOB_PASSWORD` | the web users the client's `login` signs in |

### Everything else

Scope names (`shortener.shortlink.scopes.*`), the claim that names the owner (`shortener.security.client-id-claim`, `owner`), the
audience and the access-token type are properties with defaults in `application.yaml`. The stack does not pass them through; to
change one, expose it as described under [Tunables](#tunables).

## Call the API

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
| JavaScript, TypeScript | [oauth4webapi](https://github.com/panva/oauth4webapi) | an OAuth and OpenID Connect client that lists DPoP among its features |
| Java | [Nimbus OAuth 2.0 SDK](https://connect2id.com/products/nimbus-oauth-openid-connect-sdk/examples/oauth/dpop) | DPoP proofs and sender-constrained tokens |
| .NET | [Duende](https://duendesoftware.com/blog/20230504-dpop) | DPoP in its client libraries and in the Microsoft OpenID Connect handler |
| Go | [go-dpop](https://pkg.go.dev/github.com/AxisCommunications/go-dpop) | proof generation and validation |
| Go | [conductorone/dpop](https://pkg.go.dev/github.com/conductorone/dpop) | proofs, a `net/http` client and server middleware |
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
| Load | `perf/bench.sh` | Docker, spare cores, `jq` |

The suite has unit tests (in-memory repository), integration tests (real Postgres, stand-in identity provider),
architecture tests, and tests of the deployment files (stack, profiles, alert rules, release version).

**Smoke test.** Run it against the native image:

```bash
perf/smoke.sh                   # http://localhost:8080, management on 8081
```

Against the stack: give it the address, the certificate and the management port ([the rehearsal](#rehearse-production-on-one-machine)).

**Load test.** `perf/bench.sh VARIANT IMAGE OUT_DIR` runs one image with 2 cores and 512 MB against Postgres and k6.
`perf/run-all.sh` compares the JVM and the native image.

```bash
RATES="250 500 1000 1500 2000" REPEAT=3 COOLDOWN=15 perf/bench.sh native shortener:0.21.0 perf/results/ladder
tools/run Report summary perf/results            # a column for each variant; repeated runs show the median and the range
```

- `RATES` is one run for each rate, `DURATION` (default 60s) long, with `CREATES_PER_SECOND` (default 15) creates. `REPEAT` runs each
  N times and `COOLDOWN` waits between them. `RUNS="name rate duration share|..."` still writes the runs out by hand.
- `APP_CPUSET`, `APP_CPUS` and `APP_MEMORY` (default `0-1`, `2`, `512m`) change the application's limits.
- Each run leaves `summary.json`, `metadata.json` (commit, limits, workload), the k6 summaries and the container's log. The k6
  summaries have the DPoP key and the token removed by `perf/scrub-k6-summary.sh`, which needs `jq`.

**Links the cache has not seen.** The default workload reads 300 links, so the redirect cache absorbs it. To read many:

```bash
perf/load-dataset.sh 10M        # 10M distinct codes into short_link (about 10 minutes, 2 GB), kept in perf/data/
DATASET_FILE=perf/data/codes-10M-seed1.txt perf/bench.sh native shortener:0.21.0 perf/results/dataset
```

- With `DATASET_FILE` the bench does not truncate `short_link`, and k6 reads codes of the file by position. `DATASET_HOT=H`
  with `DATASET_HOT_SHARE` (default 0.8) sends that share of the reads to the first `H` codes: a small `H` is a stampede on a
  few codes.
- `perf/profile.sh` still truncates the table, and so does `perf/bench.sh` without `DATASET_FILE`.
- Do not limit the memory of the Postgres container below the size of the data: the kernel killed its processes at 512 MB.

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
`tools/`, built by `tools/run` the first time and when a source changes (JDK 25). The one exception is
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
| `Dataset` | distinct short codes, in parallel, for loads that the redirect cache does not absorb | Kotlin |
| `ClientKeys`, `DpopCalls` | client keys, and DPoP sign-in and calls, for `DevSetup` and `Smoke` | Kotlin |
| `deploy/keycloak/DpopClient.java` | sign in and call the API with DPoP; make client keys | Java |
| `perf/bench.sh`, `run-all.sh`, `profile.sh`, `tune-connections.sh` | run the load test, profile | sh |
| `perf/scrub-k6-summary.sh` | remove the DPoP key and the token from a k6 summary | sh |
| `perf/load-dataset.sh` | generate a dataset with `Dataset` and load it into Postgres | sh |
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
script to be asked. `tools/run` works by hand with any JDK 25. Tests: `./gradlew test` (with the application's) or
`./gradlew -p tools test`.
