# Reference

Every configuration key, secret file and script, and where each is read. How to use them is in [OPERATING.md](OPERATING.md) and
[DEPLOY.md](DEPLOY.md).

- [Configuration](#configuration)
  - [The profiles](#the-profiles)
  - [`.env`: the stack's variables](#env-the-stacks-variables)
  - [Secret files](#secret-files)
  - [Development](#development)
  - [Everything else](#everything-else)
- [Scripts](#scripts)

## Configuration

Three layers, each overriding the one before:

```text
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
| `KEYCLOAK_DB_URL` | the stack's `postgres` | Keycloak's database. Changed to the replica when it is promoted ([DEPLOY.md](DEPLOY.md#losing-the-primary)) |
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
| `Report` | read GC logs, heap dumps and bench results (`summary`, `cpu`); query Prometheus | Kotlin |
| `Dataset` | distinct short codes, in parallel, for loads that the redirect cache does not absorb | Kotlin |
| `ClientKeys`, `DpopCalls` | client keys, and DPoP sign-in and calls, for `DevSetup` and `Smoke` | Kotlin |
| `deploy/keycloak/DpopClient.java` | sign in and call the API with DPoP; make client keys | Java |
| `perf/bench.sh`, `run-all.sh`, `profile.sh`, `tune-connections.sh` | run the load test (scenarios `app`, `edge`, `full`), profile | sh |
| `perf/compare-builds.sh`, `perf/builds/jvm.sh`, `perf/builds/native.sh` | run one load on several builds, and make the images of a JDK (with or without the AOT cache) and of a native image with a given GraalVM | sh |
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
| `report` | `tools/run Report` | `-Preport=summary\|cpu\|gc\|hprof\|profile\|json -Ptarget=PATH -Ptop=N` |

The tasks run with the project's JDK 25 toolchain. `devSetup` takes the defaults because Gradle has no terminal: run the
script to be asked. `tools/run` works by hand with any JDK 25. Tests: `./gradlew test` (with the application's) or
`./gradlew -p tools test`.
