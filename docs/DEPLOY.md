# Deploying

How to run the production stack, `compose.prod.yaml`, on Swarm or on one host with Compose. Why it is built this way, and
what it gives up, is in [INTERNALS.md](INTERNALS.md#deployment).

- [What you need](#what-you-need)
- [First deploy](#first-deploy)
- [Update, roll back](#update-roll-back)
- [Operate](#operate)
- [Rehearse it on one machine](#rehearse-it-on-one-machine)
- [Keycloak](#keycloak)
- [Without Swarm](#without-swarm)
- [Managed database](#managed-database)

## What you need

- One or more hosts with Docker 25 or later. Swarm for more than one.
- An image: `ghcr.io/christ008/shortener:<version>`, published by the Release workflow when you push a `v*` tag. A
  private package needs `docker login ghcr.io` on the manager, and `--with-registry-auth`, which `deploy.sh` passes.
- An identity provider whose tokens carry the `owner` claim and bind to the client's key (DPoP). The dev realm in
  `deploy/keycloak/shortener-realm.json` shows what the service needs, and must not be imported into a real one.
- A certificate and key for the host name, as PEM files.
- DNS pointing the host name at the node that runs the edge.

## First deploy

1. Settings. Copy `deploy/stack/.env.example` to `.env` and fill it in: the image version, the issuer and the key
   endpoint of the identity provider, and which hosts links may point to (`ALLOWED_TARGET_HOSTS`, or `ALLOW_ANY_TARGET=true`
   for a private instance). The application does not start without one of the two.
2. Secrets. Make a directory for them, readable only by you (`secrets/`, which Git ignores), with these files, each
   `chmod 0444` because the containers run as other users and Swarm mounts a secret with the file's mode:

   | File | Content |
   |---|---|
   | `tls_cert` | the certificate chain, PEM |
   | `tls_key` | its private key, PEM |
   | `db_postgres_password` | the database superuser, used once to create the roles |
   | `db_app_password` | `shortener_app`, which serves requests |
   | `db_migrator_password` | `shortener_migrator`, which owns the tables |
   | `db_exporter_password` | `shortener_exporter`, which reads statistics |

   ```bash
   for name in db_postgres_password db_app_password db_migrator_password db_exporter_password; do
     openssl rand -hex 24 | tr -d '\n' > "secrets/$name"
   done
   chmod 0444 secrets/*
   ```
3. Swarm. On the host: `docker swarm init`, then label the node that holds the database and publishes the edge:

   ```bash
   docker node update --label-add shortener.postgres=true <node>
   ```
4. Deploy:

   ```bash
   deploy/stack/deploy.sh 0.20.0
   OBSERVABILITY=1 deploy/stack/deploy.sh 0.20.0     # with Prometheus and the Postgres exporter
   ```

   Everything starts at once. The application restarts until the migration job has finished, which takes seconds.
5. Check: `docker service ls`, then `curl https://<host>/<any code>` answers `404`, and `perf/smoke.sh https://<host>`
   exercises every endpoint if you can sign in as the dev clients.

## Update, roll back

- **Update**: `deploy/stack/deploy.sh <version>`. It runs the migration job at the new version while the application
  still runs the old one, waits for it, then updates the application one task at a time, new before old. A task that
  does not become healthy within 20 s rolls the update back.
- **Roll back** an update that went wrong later: `deploy/stack/deploy.sh <previous version>`. Migrations are written so
  that the previous version works against the new schema, so there is nothing to undo.
- **Change the edge configuration**: edit `deploy/edge/nginx.conf` and deploy again. The configuration's name carries a
  hash of the file, so Swarm creates a new one and updates the edge.
- **Rotate a secret**: Swarm secrets cannot change. Create the new file under a new name in `compose.prod.yaml`, and
  deploy. A database password also needs `ALTER ROLE` first.

## Operate

- Logs: `docker service logs shortener_shortener` (JSON). Rotated at 10 MB, three files.
- Scale: `docker service scale shortener_shortener=3`. Rate limits and the DPoP replay cache are per task, so the
  effective limit grows with the count. Postgres allows ten connections per task, so keep `tasks x 10` below its
  `max_connections`.
- Prometheus is not published, because it has no login. Look at it from its node:

  ```bash
  docker exec $(docker ps -q -f name=shortener_prometheus) wget -qO- 'http://127.0.0.1:9090/api/v1/alerts'
  ```

  To see the interface, open an SSH tunnel to the container's address on that node, or publish it behind your own login.
  Alerts need an Alertmanager: uncomment `alerting` in `deploy/observability/prometheus.stack.yml`.
- Database: `perf/pg-diagnostics.sql` is what to run when it is slow:

  ```bash
  docker exec -i $(docker ps -q -f name=shortener_postgres) psql -U postgres -d shortener < perf/pg-diagnostics.sql
  ```
- Backups: the stack has none. Until you add them, run `pg_dump` from a scheduled job, or use a managed database.
- Disk: the database is on the node's local volume `shortener_postgres-data`.

## Rehearse it on one machine

Everything, including a Keycloak with the dev realm and a self-signed certificate:

```bash
./gradlew bootBuildImage                       # or use a published image
deploy/stack/local/prepare.sh                  # throwaway secrets and a certificate for localhost; runs deploy/keycloak/dev-setup too
docker swarm init --advertise-addr 127.0.0.1 --listen-addr 127.0.0.1:2377
docker node update --label-add shortener.postgres=true "$(docker node ls -q)"
COMPOSE_FILES="compose.prod.yaml deploy/stack/local/compose.local.yaml" RESOLVE_IMAGE=never deploy/stack/deploy.sh 0.20.0
```

The `--listen-addr` keeps the Swarm manager off the network. `prepare.sh` runs `deploy/keycloak/dev-setup --yes` if it has
not been run, because the rehearsal's Keycloak needs the dev realm and its password. Smoke test it, trusting the
certificate as any Java program does:

```bash
keytool -importcert -noprompt -alias local -file secrets/tls_cert -keystore ts.p12 -storetype PKCS12 -storepass changeit
JAVA_TOOL_OPTIONS="-Djavax.net.ssl.trustStore=$PWD/ts.p12 -Djavax.net.ssl.trustStorePassword=changeit" \
  MGMT=http://localhost:8081 perf/smoke.sh https://localhost
```

Remove it with `docker stack rm shortener`, then `docker swarm leave --force`.

The rehearsal publishes the management port in host mode, which stops a second application task from starting on the
same node. Drop that `ports` entry from `compose.local.yaml` to run two.

## Keycloak

For a stack with no identity provider of its own, `compose.prod.keycloak.yaml` adds Keycloak in production mode, behind the edge.
It is optional. An organization that has an identity provider points `ISSUER_URI` and `JWKS_URI` at it and skips this. Why it is
built this way: [ADR 0025](adr/0025-keycloak-in-the-stack.md).

What you get: a `keycloak` service with a database of its own in the stack's Postgres, a realm with two clients, and the edge
forwarding the `shortener` realm, its static files and nothing else. The master realm and the administration console are not
reachable from outside.

1. **Build the image** on the node, or in a registry if there is more than one node (`KEYCLOAK_IMAGE` says where):

   ```bash
   docker build -t shortener-keycloak:26.7.5 deploy/keycloak
   ```
2. **Make the keys and the realm.** The realm trusts the public key of each client and holds nothing secret:

   ```bash
   java deploy/keycloak/DpopClient.java keygen demo-client  > demo.keys
   java deploy/keycloak/DpopClient.java keygen admin-client > admin.keys
   sed -n 2p demo.keys  > demo-client.public.json
   sed -n 2p admin.keys > admin-client.public.json
   ./gradlew productionRealm -Pdemo=demo-client.public.json -Padmin=admin-client.public.json
   ```

   Keep line 1 of `admin.keys` (the private key) on your machine alone: it can take down any link. Line 1 of `demo.keys` is
   published with the instance, so that visitors can try it, and they all act as `demo-client`.
3. **Two more secrets**, mode `0444` like the others: `db_keycloak_password` (the role `keycloak`, created with its database the
   first time Postgres starts) and `keycloak_admin_password` (the bootstrap administrator of the master realm).
4. **Settings** in `.env`: `PUBLIC_URL`, the address the world uses, which becomes the issuer, and the issuer and the key
   endpoint. The application reads keys over the stack's network, not through the edge:

   ```
   PUBLIC_URL=https://shortener.example.com
   ISSUER_URI=https://shortener.example.com/realms/shortener
   JWKS_URI=http://keycloak:8080/realms/shortener/protocol/openid-connect/certs
   ```
5. **Deploy** with `KEYCLOAK=1 deploy/stack/deploy.sh <version>`. Keycloak takes about a minute to be ready.

Things to know:

- **The realm is imported once**, the first time Keycloak starts, and never again. To change it afterwards use `kcadm.sh` from
  the node:

  ```bash
  docker exec -it $(docker ps -q -f name=shortener_keycloak) /opt/keycloak/bin/kcadm.sh config credentials \
    --config /tmp/kcadm.config --server http://localhost:8080 --realm master --user admin
  ```

  The administration console is the same: it is not on the edge, so reach it from the node, for example through an SSH tunnel.
- **A bad realm file stops Keycloak starting.** It fails closed, and the log says which field.
- **Adding it to a stack that already has data:** Postgres runs its initialisation scripts only for a new data directory, so run
  the script once by hand: `docker exec $(docker ps -q -f name=shortener_postgres) sh /docker-entrypoint-initdb.d/30-keycloak-database.sh`
  (it needs the file secret to be mounted, so deploy first).
- **Failed sign-ins are logged** by Keycloak as warnings with the client and the address, and the edge limits the token endpoint to
  ten requests a second per address.
- **Back it up with the database.** Keycloak's database holds the realm after import, and its sessions.
- **Use the standard port.** In a rehearsal on another published port, a call through the edge was refused with
  `invalid_dpop_proof` while the same call on 443 worked. The cause was not investigated: serve the edge on 80 and 443.

## Without Swarm

`docker compose` runs the same file on one host and honours more of it, including `no-new-privileges`. It has no rolling
update: `up -d` replaces containers. Migrate first:

```bash
docker compose -f compose.prod.yaml up -d postgres
docker compose -f compose.prod.yaml run --rm migrate
docker compose -f compose.prod.yaml up -d
```

It needs the variables of `.env` (Compose reads that file itself) and the same secret files.

## Managed database

Drop the `postgres` service and the `db_postgres_password` secret. Create the roles with `deploy/postgres/bootstrap.sql`
as a superuser, give them the passwords in the secret files, and set in `.env`:

```
SPRING_DATASOURCE_URL=jdbc:postgresql://db.example.com:5432/shortener?sslmode=verify-full
SPRING_FLYWAY_URL=jdbc:postgresql://db.example.com:5432/shortener?sslmode=verify-full
```

The database then gets backups, replication and failover from its provider, which this stack does not provide.
