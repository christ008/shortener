# Deploying

Runbook for the production stack, `deploy/stack/compose.prod.yaml`, on Swarm or on one host with Compose. The steps in order,
with a checklist, are in [OPERATING.md](OPERATING.md#go-to-production), and every variable and secret is in
[REFERENCE.md](REFERENCE.md#configuration). This page has the detail behind them. Design and trade-offs:
[INTERNALS.md](INTERNALS.md#deployment).

- [Requirements](#requirements)
- [First deploy](#first-deploy)
- [Update, roll back](#update-roll-back)
- [Operate](#operate)
- [Certificate](#certificate)
- [Keycloak](#keycloak)
- [Backups and a replica](#backups-and-a-replica)
- [Without Swarm](#without-swarm)
- [Managed database](#managed-database)

## Requirements

- Docker 25 or later on each host. Swarm for more than one.
- The image `ghcr.io/christ008/shortener:<version>`, published by the Release workflow on a `v*` tag. It is arm64 ([ADR 0036](adr/0036-jvm-image-for-arm64.md)), so the hosts are
  arm64 (AWS Graviton, Oracle's Ampere); on an amd64 host build it there with `./gradlew bootBuildImage` and set `SHORTENER_IMAGE`. A private package needs
  `docker login ghcr.io` on the manager. `deploy.sh` passes `--with-registry-auth`.
- `cosign` on the manager. `deploy.sh` verifies the image's signature before it deploys anything, and `VERIFY_SIGNATURE=never`
  skips that (the [one-machine rehearsal](OPERATING.md#rehearse-production-on-one-machine) does, because its image is built locally).
- An identity provider whose tokens carry the `owner` claim and bind to the client's key (DPoP). The dev realm
  `deploy/keycloak/shortener-realm.json` shows what is needed. Never import it into a real one.
- A certificate and key for the host name, as PEM files, and DNS pointing at the node that runs the edge.

## First deploy

1. **Settings.** Copy `deploy/stack/.env.example` to `.env` at the repository root and fill in the image version, the issuer and
   key endpoint of the identity provider, and either `ALLOWED_TARGET_HOSTS` or `ALLOW_ANY_TARGET=true`. The application does not
   start without one. What each variable does, and the optional ones, are in
   [REFERENCE.md](REFERENCE.md#env-the-stacks-variables).
2. **Secrets.** Create `secrets/` at the repository root (git-ignored): the certificate chain as `tls_cert` and its key as
   `tls_key`, both PEM, and random values for the rest. Each file is `chmod 0444`. What each file is and who reads it:
   [REFERENCE.md](REFERENCE.md#secret-files).

   ```bash
   for name in db_postgres_password db_app_password db_migrator_password db_exporter_password dpop_nonce_secret; do
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
   deploy/stack/deploy.sh <version>
   OBSERVABILITY=1 deploy/stack/deploy.sh <version>     # with Prometheus and the Postgres exporter
   ```

   Everything starts at once. The application restarts until the migration job finishes (seconds).
5. **Check.** `docker service ls`; `curl https://<host>/<any code>` answers `404`; `perf/smoke.sh https://<host>` exercises
   every endpoint if you can sign in as the dev clients.

## Update, roll back

- **Update:** `deploy/stack/deploy.sh <version>`. It verifies the signature of that version, runs the migration job at the new version, waits for it, then updates the
  application one task at a time, new before old. A task not healthy within 20 s rolls the update back.
- **Roll back:** `deploy/stack/deploy.sh <previous version>`. Nothing to undo in the schema.
- **Edge configuration:** edit `deploy/edge/nginx.conf`, deploy again.
- **Rotate a secret:** create the file under a new name in `deploy/stack/compose.prod.yaml`, deploy. A database password needs
  `ALTER ROLE` first. Rotating `dpop_nonce_secret` makes clients ask for a nonce again once, while the instances disagree.

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
- **Backups:** none unless you add the [backups and replica overlay](#backups-and-a-replica), or use a managed database.
- **Disk:** the database is on the node's local volume `shortener_postgres-data`.

## Certificate

`deploy/stack/certificate.sh` issues the edge's certificate with Let's Encrypt and keeps it renewed ([CLOUD.md](CLOUD.md)). It needs `certbot`, root,
DNS for the host name pointing at this node, and port 80 open. The challenge is HTTP-01, answered by the edge, so there are no wildcard names.

1. `sudo deploy/stack/certificate.sh placeholder <host>` writes a self-signed `tls_cert` and `tls_key` so that the edge can start, and makes the
   challenge directory (`ACME_DIR`, default `/srv/shortener/acme`).
2. `ACME=1 deploy/stack/deploy.sh <version>` adds `deploy/stack/overlays/compose.acme.yaml`, which mounts that directory in the edge. Put `ACME=1` (and
   `KEYCLOAK=1` and the other flags) in `.env`: the renewal deploys again and reads them from there.
3. `sudo deploy/stack/certificate.sh issue <host> <email>` runs `certbot certonly --webroot`, copies the files into `secrets/` and deploys. Try it first with
   `STAGING=1`: the staging service has generous limits and no client trusts its certificates.

Going from `STAGING=1` to the real service (or back) is one more `issue`: certbot keeps any certificate that is not due for renewal, whoever issued it, so the script
sees that the existing one came from the other service and issues anyway. Over a certificate from the same service it does not, to stay inside the limits of Let's Encrypt.

Renewal is certbot's own timer (`systemctl list-timers certbot.timer`), about thirty days before the expiry. `issue` installs the hook
`/etc/letsencrypt/renewal-hooks/deploy/shortener`, which runs `certificate.sh install`: it copies the new files and runs `deploy.sh` with the running version. A secret
is immutable, so `deploy.sh` names the two certificate secrets after a hash of their contents (`TLS_HASH`), and the edge is replaced by a new task with the new secrets,
which takes a few seconds on one node. Old secrets stay until `docker secret rm`; list them with `docker secret ls`. Test the renewal without waiting: `sudo certbot renew --dry-run`.

Tried on 2026-10-11 on a `t4g.small` in `us-east-2`, with `STAGING=1`: `issue` got a certificate through the edge, `install` rotated the secrets
(`tls_cert_<hash>`, mounted at `/run/secrets/tls_cert` as before) and the edge served the new certificate, and `certbot renew --dry-run` passed. The stack ran
with the application running (the signed image of 0.22.1), and the real service and a renewal 30 days on were not tried. `issue HOST -` registers without an email,
for a trial: with a real certificate give one, because that is where Let's Encrypt writes before an expiry.

## Keycloak

`deploy/stack/overlays/compose.keycloak.yaml` adds Keycloak in production mode behind the edge, for a stack with no identity provider. With
your own provider, point `ISSUER_URI` and `JWKS_URI` at it and skip this. [ADR 0025](adr/0025-keycloak-in-the-stack.md).

It adds a `keycloak` service with its own database in the stack's Postgres, a realm with two clients, and an edge route for
the `shortener` realm and its static files only. The master realm and the console are not reachable from outside.

1. **Build the image** on the node, or push it to a registry (`KEYCLOAK_IMAGE`):

   ```bash
   docker build -t shortener-keycloak:26.8.0 deploy/keycloak
   ```
2. **Make the keys and the realm.** The realm holds only public keys:

   ```bash
   java deploy/keycloak/DpopClient.java keygen demo-client  > demo.keys
   java deploy/keycloak/DpopClient.java keygen admin-client > admin.keys
   sed -n 2p demo.keys  > demo-client.public.json
   sed -n 2p admin.keys > admin-client.public.json
   ./gradlew productionRealm -Pdemo=demo-client.public.json -Padmin=admin-client.public.json
   ```

   Keep line 1 of `admin.keys` private, on your machine and encrypted at rest, and never on the VM: it can take down any link. Line 1 of `demo.keys` is published so visitors can try the
   instance as `demo-client`.
3. **Two more secrets** (`0444`): `db_keycloak_password` (role `keycloak`, created with its database at first Postgres
   start) and `keycloak_admin_password` (bootstrap administrator of the master realm).
4. **Settings** in `.env`. The application reads keys over the stack's network, not through the edge:

   ```dotenv
   PUBLIC_URL=https://shortener.example.com
   ISSUER_URI=https://shortener.example.com/realms/shortener
   JWKS_URI=http://keycloak:8080/realms/shortener/protocol/openid-connect/certs
   ```
5. **Deploy:** `KEYCLOAK=1 deploy/stack/deploy.sh <version>`. Keycloak is ready after about a minute.

Memory. The overlay limits Keycloak to `KEYCLOAK_MEMORY` (default `1G`) and lets the heap take `KEYCLOAK_HEAP_PERCENT` (default `65`) of it. Keycloak's
documentation ([containers](https://www.keycloak.org/server/containers), [sizing](https://www.keycloak.org/high-availability/single-cluster/concepts-memory-and-cpu-sizing))
sizes a pod at 1250 MB with 10,000 cached sessions, about 300 MB of it outside the heap; says a limit of at least 750 MB is what gives the heap the old
maximum of 512 MB, and recommends 2 GB for a small production deployment. A small host with a realm of a few clients and no users needs much less than that sizing,
but how much less is not in the documentation: set `KEYCLOAK_MEMORY=768M` and `KEYCLOAK_HEAP_PERCENT=50` (a 384 MB heap, about 300 MB outside it) and read the use
with `docker stats` before going lower. The first start, which imports the realm and migrates the database, is the peak, and the caches fill again after every restart.

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
- **Its database is backed up with the shortener's** when the [backups and replica overlay](#backups-and-a-replica) is on:
  pgBackRest copies the whole Postgres instance, and the replica streams all of it.
- **The edge may be published on another port.** A DPoP proof names the URL with its port, and the application rebuilds that URL
  from the forwarded headers. The edge sends the port of the `Host` header as `X-Forwarded-Port` (`ComposeStackTest`), because
  without it Tomcat assumes 443 and a call on another port was refused with `invalid_dpop_proof` while the same call on 443
  worked. `DpopIntegrationTest` reproduces the refusal at the application and shows the header fixing it, and the smoke test
  passed through the edge on 9443 ([Verified](INTERNALS.md#verified)).

### The administrator

The bootstrap administrator (`admin`, with the password of the `keycloak_admin_password` secret) signs in with a password alone, and is for making the first real one.
Make it, from the node, with the stack running:

```bash
deploy/keycloak/admin-user create ana
```

It prints a password that works once. At the first sign-in to the console Keycloak asks for a new password and a code from an authenticator app (any TOTP app), and from then on
a password is not enough: every user the master realm gets has to set the app up (`CONFIGURE_TOTP` is a default action), and five wrong passwords slow the account down. When
`ana` has signed in with the app, retire the bootstrap one:

```bash
deploy/keycloak/admin-user retire ana
```

`retire` refuses until `ana` has an authenticator app and the administrator role, so the stack is not left without a way in. It disables `admin`; delete it in the console once you are sure.
Names are lower case: Keycloak keeps them so.

`kcadm.sh` cannot answer a one-time code, so with a user that has the app it cannot sign in with a password: use the console ([through a tunnel](#the-console-through-a-tunnel)) for what the notes list as `kcadm.sh`.
Tried on Keycloak 26.8.0 (2026-10-12): a password alone asks for the code, a wrong code is refused, the right one signs in, and `retire` disables the bootstrap administrator and refuses before the app is set up.

### The console through a tunnel

The console is not on the Internet, and Keycloak's address on the Swarm network cannot be reached from the node, so a tunnel to it does not work. `CONSOLE=1`, beside `KEYCLOAK=1` ([ADR 0041](adr/0041-keycloaks-console-through-an-ssh-tunnel.md)),
adds to the edge `/admin/` and the master realm at the site's own address, for connections from the node and for nobody else. From anywhere else they answer `404`. Put `CONSOLE=1` in `.env`, with the other flags, and deploy.

To sign in, from your machine (the local port 443 needs root, and the name must point at the local end while the tunnel is open):

```bash
sudo sh -c 'echo "127.0.0.1 <host>" >> /etc/hosts'
```

```bash
sudo ssh -i <key> -o UserKnownHostsFile=$HOME/.ssh/known_hosts -N -L 443:localhost:443 ubuntu@<node>
```

Then open `https://<host>/admin/master/console/`, with the real certificate. When you are done, stop the tunnel (`Ctrl-C`) and remove the line:

```bash
sudo sed -i '/127.0.0.1 <host>$/d' /etc/hosts
```

The guard is the address `172.18.0.1` in `deploy/edge/console.conf`, the gateway of `docker_gwbridge`; if `docker network inspect docker_gwbridge` says another, change it there, or the console stays closed.
Tried on the AWS VM (2026-10-12): `404` from the Internet and `200` through the tunnel for `/admin/`, the master realm's metadata and the `shortener` realm. Not tried: the sign-in in a browser on the port 443, and the code of an authenticator app.

### Administrators in the API

The administrator scope (`shortlinks:admin`) reads and disables every client's links, and the API does not take a token with it on trust ([ADR 0031](adr/0031-human-administrators-need-a-second-factor.md)):

- **A service account** (`admin-client`) authenticates with a key and has no second factor to show. It must be listed in `ADMIN_SERVICE_CLIENTS` (default `admin-client`), and a client listed there must not be able to sign a person in.
- **A person** needs a token whose `acr` claim is a level of 2 or more (`shortener.security.admin.min-level`). Otherwise the API answers `401` with `error="insufficient_user_authentication"` and `acr_values="2"` (RFC 9470), and a client signs the person in again with that `acr_values`.

Keycloak's own flows do not write it: a person who gave a one-time code and one who did not both get `acr: 1`. The realm that gives people the scope (the UI's) must take
the flow of `deploy/keycloak/realm-step-up.json` (its `authenticationFlows`, `authenticatorConfig` and `browserFlow`) and its client must send `acr_values=2`, or have it as its default. Tried on
Keycloak 26.8.0: a person who asks for level 2 and has a code configured is asked for it and gets `acr: 2`; one who does not ask gets `1`, as a service account does (the flow gives the levels no names: a name such as `pwd` would label a client that signs in with a key); one with no code configured is made to set it up first.

### Registering a client

A client is a key. Nobody registers without an invitation, and an invitation is made by an administrator in the console: Clients, Initial access token, Create, with a count of 1 and
a deadline (seven days is plenty). The token is shown once. Give it to the person by a channel you trust.

The person, with a JDK 17 or newer and `deploy/keycloak/DpopClient.java`:

```bash
export ISSUER=https://shortener.example.com/realms/shortener TOKEN_URL=$ISSUER/protocol/openid-connect/token
java deploy/keycloak/DpopClient.java register ana INVITATION ana.jwk.json
java deploy/keycloak/DpopClient.java call ana.jwk.json CLIENT_ID POST https://shortener.example.com/api/short-links '{"targetUrl":"https://example.com/a/long/path"}'
```

`register` makes the key and writes its private half to `ana.jwk.json` (readable by its owner alone, never overwritten) before it asks, registers the public half, and prints the client id. A refused
registration leaves no file. The person is told to keep the file: it is the only way to be that client.

What the client gets is the realm's, not its own: `shortlinks:create`, `read` and `delete`, no custom codes (`claim`), no administration; the audience `shortener-api`; an `owner` that is its client id
(a UUID, which is what `created_by` shows: the name it gave is `client_name` in the console); tokens that live five minutes and are bound to a key of the client's own, since the application accepts no other. It cannot ask for more: another
scope, a mapper of its own or a client id of its choosing is refused ([ADR 0039](adr/0039-clients-register-themselves-with-an-invitation.md)).

Limits and how to end one:

- **At most 50 clients** in the realm, the operator's included (`Max Clients Limit`, in the console: Clients, Client registration, Client registration policies). A used invitation is gone, an unused one stays until its deadline;
  delete it under Initial access token.
- **Six requests a minute per address** to the registration endpoint at the edge. Keycloak's own endpoint and SAML answer `404`.
- **To end a client:** disable or delete it in the console. Its token stops working within five minutes. To take a link down, `PATCH` it with the administrator client.
- **A `jwks_uri`** instead of the key itself is accepted, and fetched by Keycloak: [F-14](THREAT_MODEL.md#f-14-a-client-that-registers-itself-can-name-a-jwks_uri).
- **An existing stack** keeps the realm it imported. Bring it in line with the new template in the console (a client scope `shortener-owner` with a *User Session Note* mapper of the note `client_id` into the claim `owner`, as default
  scope with `shortlinks:create`, `read` and `delete`; the registration policies above; and remove the `owner` mapper of each client), or import the new realm into a new stack.

## Backups and a replica

`deploy/stack/overlays/compose.postgres-ha.yaml` adds pgBackRest backups and a streaming replica ([ADR 0028](adr/0028-postgres-backups-and-a-replica.md)).

1. Build the image on the node, or push it to a registry if there is more than one node (`POSTGRES_IMAGE`):

   ```bash
   docker build -t shortener-postgres:18.6 deploy/postgres
   ```
2. Create the secret `db_replicator_password`, mode `0444`.
3. Label the node that runs the replica:

   ```bash
   docker node update --label-add shortener.postgres-replica=true <node>
   ```
4. Deploy: `POSTGRES_HA=1 deploy/stack/deploy.sh <version>`. On a database that already exists, create the role by hand:
   `docker exec <postgres container> sh /docker-entrypoint-initdb.d/35-replication.sh`, then
   `psql -U postgres -c 'SELECT pg_reload_conf()'`.
5. Make the first backup, once: `deploy/postgres/backup init`. The WAL archiving alert fires until it runs.
6. Schedule the rest on the node that runs Postgres. `deploy/postgres/backup.cron` is a file for `/etc/cron.d`: a full backup on Sundays and a differential one on the
   other days, at 03:17 of the server's clock. Install it for the repository where it is (it points at `/srv/shortener`), from the repository's root. Two full backups are kept:

   ```bash
   sed "s|/srv/shortener|$PWD|" deploy/postgres/backup.cron | sudo tee /etc/cron.d/shortener-backup >/dev/null
   ```

   Each run writes one line to the system log saying whether it worked (`journalctl -t shortener-backup`), because pgBackRest prints nothing on success.
7. Test a restore, now and periodically: `deploy/postgres/backup restore-test` restores the latest backup into a throwaway
   container and prints how many links the copy has and when the newest was created. Compare them with the database. It checks
   only the shortener's database: Keycloak's is in the same backup, and nothing counts what it holds.

Backups without the replica: leave the node without the `shortener.postgres-replica` label (step 3). `postgres-replica` then stays pending, `0/1`, and takes no memory; the
backups, the archiving and `restore-test` work as above. A 2 GB host has no room for a second Postgres.

Limits:

- The repository is a volume on the primary's node. It restores to within five minutes and does not survive losing the node.
  For S3 or SFTP, set `repo1-type` and its options in `deploy/postgres/pgbackrest.conf`, a Docker config: rotate it under a new name.
- Nothing alerts when a backup did not run or failed. Look at `journalctl -t shortener-backup` and `deploy/postgres/backup info`.
- The replica is read only, and the application does not read from it.

### Losing the primary

1. Check the replica is caught up: `docker exec -u postgres <replica> psql -tAc "select pg_last_wal_replay_lsn(), pg_is_in_recovery()"`.
2. Stop the primary: `docker service scale shortener_postgres=0`.
3. Promote the replica: `docker exec -u postgres <replica> psql -tAc "select pg_promote(wait => true)"`.
4. In `.env`, set `SPRING_DATASOURCE_URL` and `SPRING_FLYWAY_URL` to `jdbc:postgresql://postgres-replica:5432/shortener`, and with
   Keycloak `KEYCLOAK_DB_URL` to `jdbc:postgresql://postgres-replica:5432/keycloak`. Then `deploy/stack/deploy.sh <version>`.
5. Later, rebuild a replica from the new primary, or restore the old primary's volume from a backup and make it the replica.

## Without Swarm

`docker compose` runs the same file on one host. There is no rolling update: `up -d` replaces containers. Migrate first:

```bash
compose="docker compose --env-file .env -f deploy/stack/compose.prod.yaml"
$compose up -d postgres
$compose run --rm migrate
$compose up -d
```

It needs the variables of `.env` (`--env-file`, because Compose looks for `.env` next to the compose file) and the same secret files.

## Managed database

Drop the `postgres` service and the `db_postgres_password` secret. Run `deploy/postgres/bootstrap.sql` as a superuser, give
the roles the passwords in the secret files, and set in `.env`:

```dotenv
SPRING_DATASOURCE_URL=jdbc:postgresql://db.example.com:5432/shortener?sslmode=verify-full
SPRING_FLYWAY_URL=jdbc:postgresql://db.example.com:5432/shortener?sslmode=verify-full
```
