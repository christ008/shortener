# Deploying

Runbook for the production stack, `compose.prod.yaml`, on Swarm or on one host with Compose. Design and trade-offs:
[INTERNALS.md](INTERNALS.md#deployment).

- [Requirements](#requirements)
- [First deploy](#first-deploy)
- [Update, roll back](#update-roll-back)
- [Operate](#operate)
- [Rehearse it on one machine](#rehearse-it-on-one-machine)
- [Keycloak](#keycloak)
- [Without Swarm](#without-swarm)
- [Managed database](#managed-database)

## Requirements

- Docker 25 or later on each host. Swarm for more than one.
- The image `ghcr.io/christ008/shortener:<version>`, published by the Release workflow on a `v*` tag. A private package needs
  `docker login ghcr.io` on the manager. `deploy.sh` passes `--with-registry-auth`.
- An identity provider whose tokens carry the `owner` claim and bind to the client's key (DPoP). The dev realm
  `deploy/keycloak/shortener-realm.json` shows what is needed. Never import it into a real one.
- A certificate and key for the host name, as PEM files, and DNS pointing at the node that runs the edge.

## First deploy

1. **Settings.** Copy `deploy/stack/.env.example` to `.env` and fill in the image version, the issuer and key endpoint of the
   identity provider, and either `ALLOWED_TARGET_HOSTS` or `ALLOW_ANY_TARGET=true`. The application does not start without one.
2. **Secrets.** Create `secrets/` (git-ignored) with these files, each `chmod 0444` (Swarm mounts a secret with the file's
   mode, and the containers run as other users):

   | File | Content |
   |---|---|
   | `tls_cert` | certificate chain, PEM |
   | `tls_key` | private key, PEM |
   | `db_postgres_password` | database superuser, used once to create the roles |
   | `db_app_password` | `shortener_app`, serves requests |
   | `db_migrator_password` | `shortener_migrator`, owns the tables |
   | `db_exporter_password` | `shortener_exporter`, reads statistics |

   ```bash
   for name in db_postgres_password db_app_password db_migrator_password db_exporter_password; do
     openssl rand -hex 24 | tr -d '\n' > "secrets/$name"
   done
   chmod 0444 secrets/*
   ```
3. **Swarm.** `docker swarm init`, then label the node that holds the database and publishes the edge:

   ```bash
   docker node update --label-add shortener.postgres=true <node>
   ```
4. **Deploy.**

   ```bash
   deploy/stack/deploy.sh 0.21.0
   OBSERVABILITY=1 deploy/stack/deploy.sh 0.21.0     # with Prometheus and the Postgres exporter
   ```

   Everything starts at once. The application restarts until the migration job finishes (seconds).
5. **Check.** `docker service ls`; `curl https://<host>/<any code>` answers `404`; `perf/smoke.sh https://<host>` exercises
   every endpoint if you can sign in as the dev clients.

## Update, roll back

- **Update:** `deploy/stack/deploy.sh <version>`. It runs the migration job at the new version, waits for it, then updates the
  application one task at a time, new before old. A task not healthy within 20 s rolls the update back.
- **Roll back:** `deploy/stack/deploy.sh <previous version>`. Nothing to undo in the schema.
- **Edge configuration:** edit `deploy/edge/nginx.conf`, deploy again.
- **Rotate a secret:** create the file under a new name in `compose.prod.yaml`, deploy. A database password needs
  `ALTER ROLE` first.

## Operate

- **Logs:** `docker service logs shortener_shortener` (JSON; 10 MB, three files).
- **Scale:** `docker service scale shortener_shortener=3`. Rate limits and the DPoP replay cache are per task. Each task
  uses ten Postgres connections: keep `tasks x 10` below `max_connections`.
- **Prometheus** is not published (no login). From its node:

  ```bash
  docker exec $(docker ps -q -f name=shortener_prometheus) wget -qO- 'http://127.0.0.1:9090/api/v1/alerts'
  ```

  For the interface, use an SSH tunnel to the container's address on that node. Alerts need an Alertmanager: uncomment
  `alerting` in `deploy/observability/prometheus.stack.yml`.
- **Database diagnostics:**

  ```bash
  docker exec -i $(docker ps -q -f name=shortener_postgres) psql -U postgres -d shortener < perf/pg-diagnostics.sql
  ```
- **Backups:** none in the stack. Run `pg_dump` from a scheduled job, or use a managed database.
- **Disk:** the database is on the node's local volume `shortener_postgres-data`.

## Rehearse it on one machine

Everything, with a dev-realm Keycloak and a self-signed certificate:

```bash
./gradlew bootBuildImage                       # or use a published image
deploy/stack/local/prepare.sh                  # throwaway secrets and a localhost certificate; runs dev-setup --yes if needed
docker swarm init --advertise-addr 127.0.0.1 --listen-addr 127.0.0.1:2377
docker node update --label-add shortener.postgres=true "$(docker node ls -q)"
COMPOSE_FILES="compose.prod.yaml deploy/stack/local/compose.local.yaml" RESOLVE_IMAGE=never deploy/stack/deploy.sh 0.21.0
```

`--listen-addr` keeps the manager off the network. Smoke test, trusting the certificate:

```bash
keytool -importcert -noprompt -alias local -file secrets/tls_cert -keystore ts.p12 -storetype PKCS12 -storepass changeit
JAVA_TOOL_OPTIONS="-Djavax.net.ssl.trustStore=$PWD/ts.p12 -Djavax.net.ssl.trustStorePassword=changeit" \
  MGMT=http://localhost:8081 perf/smoke.sh https://localhost
```

Remove it: `docker stack rm shortener`, then `docker swarm leave --force`.

The rehearsal publishes the management port in host mode, so a second application task cannot start on the same node. Drop
that `ports` entry from `compose.local.yaml` to run two.

## Keycloak

`compose.prod.keycloak.yaml` adds Keycloak in production mode behind the edge, for a stack with no identity provider. With
your own provider, point `ISSUER_URI` and `JWKS_URI` at it and skip this. [ADR 0025](adr/0025-keycloak-in-the-stack.md).

It adds a `keycloak` service with its own database in the stack's Postgres, a realm with two clients, and an edge route for
the `shortener` realm and its static files only. The master realm and the console are not reachable from outside.

1. **Build the image** on the node, or push it to a registry (`KEYCLOAK_IMAGE`):

   ```bash
   docker build -t shortener-keycloak:26.7.5 deploy/keycloak
   ```
2. **Make the keys and the realm.** The realm holds only public keys:

   ```bash
   java deploy/keycloak/DpopClient.java keygen demo-client  > demo.keys
   java deploy/keycloak/DpopClient.java keygen admin-client > admin.keys
   sed -n 2p demo.keys  > demo-client.public.json
   sed -n 2p admin.keys > admin-client.public.json
   ./gradlew productionRealm -Pdemo=demo-client.public.json -Padmin=admin-client.public.json
   ```

   Keep line 1 of `admin.keys` private: it can take down any link. Line 1 of `demo.keys` is published so visitors can try the
   instance as `demo-client`.
3. **Two more secrets** (`0444`): `db_keycloak_password` (role `keycloak`, created with its database at first Postgres
   start) and `keycloak_admin_password` (bootstrap administrator of the master realm).
4. **Settings** in `.env`. The application reads keys over the stack's network, not through the edge:

   ```
   PUBLIC_URL=https://shortener.example.com
   ISSUER_URI=https://shortener.example.com/realms/shortener
   JWKS_URI=http://keycloak:8080/realms/shortener/protocol/openid-connect/certs
   ```
5. **Deploy:** `KEYCLOAK=1 deploy/stack/deploy.sh <version>`. Keycloak is ready after about a minute.

Notes:

- **The realm is imported once**, at the first start. To change it afterwards use `kcadm.sh` from the node:

  ```bash
  docker exec -it $(docker ps -q -f name=shortener_keycloak) /opt/keycloak/bin/kcadm.sh config credentials \
    --config /tmp/kcadm.config --server http://localhost:8080 --realm master --user admin
  ```

  The console is not on the edge: reach it from the node, for example through an SSH tunnel.
- **A bad realm file stops Keycloak starting.** The log names the field.
- **Adding it to a stack that has data:** run the init script once, after deploying (it needs the mounted secret):
  `docker exec $(docker ps -q -f name=shortener_postgres) sh /docker-entrypoint-initdb.d/30-keycloak-database.sh`
- **Failed sign-ins** are logged by Keycloak as warnings with client and address. The edge limits the token endpoint to ten
  requests a second per address.
- **Back it up with the database.**
- **Serve the edge on ports 80 and 443.** On another published port a call was refused with `invalid_dpop_proof` while the
  same call on 443 worked. The cause was not investigated.

## Without Swarm

`docker compose` runs the same file on one host. There is no rolling update: `up -d` replaces containers. Migrate first:

```bash
docker compose -f compose.prod.yaml up -d postgres
docker compose -f compose.prod.yaml run --rm migrate
docker compose -f compose.prod.yaml up -d
```

It needs the variables of `.env` and the same secret files.

## Managed database

Drop the `postgres` service and the `db_postgres_password` secret. Run `deploy/postgres/bootstrap.sql` as a superuser, give
the roles the passwords in the secret files, and set in `.env`:

```
SPRING_DATASOURCE_URL=jdbc:postgresql://db.example.com:5432/shortener?sslmode=verify-full
SPRING_FLYWAY_URL=jdbc:postgresql://db.example.com:5432/shortener?sslmode=verify-full
```
