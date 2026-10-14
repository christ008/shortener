# A public instance in the cloud

The instance anyone can try, <https://shortener.codes>, and the plan it follows. The production stack is rehearsed on one machine ([DEPLOY.md](DEPLOY.md)).

> [!NOTE]
> It is running, with no promise of availability. What was run, and what is still missing, is under [Status](#status).

- [Goal](#goal)
- [Shape](#shape)
- [To decide](#to-decide)
- [Status](#status)
- [Steps](#steps)
- [Safety](#safety)
- [Cost and teardown](#cost-and-teardown)

## Goal

Someone with a terminal and a JDK 17 or newer follows the README against a real URL: gets a token, creates a link, follows
it, and sees that another client cannot see it.

## Shape

One arm64 VM (Ubuntu 24.04 on AWS, a Graviton `t4g.small` in `us-east-2`: 2 vCPU and 2 GB) running `deploy/stack/compose.prod.yaml` on a single-node Swarm. The first choice was Oracle's Always Free Ampere A1, which is the same shape and costs nothing, but it had no capacity, so the plan went to AWS.

```mermaid
flowchart LR
    visitor(["Visitor"]) -->|"HTTPS 443"| edge["nginx edge"]
    subgraph vm["One arm64 VM"]
        edge --> app["shortener x2"]
        edge --> idp["Keycloak (production realm)"]
        app --> db[("Postgres")]
        idp --> db
        migrate["migration job"] --> db
    end
    app -. "fetches signing keys" .-> idp
```

- **Edge:** nginx with a Let's Encrypt certificate for one host name, issued over HTTP-01 and renewed by certbot's timer ([DEPLOY.md](DEPLOY.md#certificate)).
- **Identity:** Keycloak with the production realm, behind the edge under `/realms/shortener` ([DEPLOY.md](DEPLOY.md#keycloak)).
- **Database:** the stack's Postgres on the VM's disk, backed up by the [backups overlay](DEPLOY.md#backups-and-a-replica), Keycloak's database included, to a repository off the VM.
- **Image:** `ghcr.io/christ008/shortener:<version>`, arm64 only ([ADR 0036](adr/0036-jvm-image-for-arm64.md)). Graviton is arm64, and 20% cheaper than the x86 instance of the same size. An amd64 host builds its own with `./gradlew bootBuildImage`.
- **Memory:** the limits of the stack add up to about 3.5 GB with Keycloak at its default 1 GB and the backups overlay. Measured on a 2 GB `t4g.small` with Keycloak at
  `KEYCLOAK_MEMORY=768M` and `KEYCLOAK_HEAP_PERCENT=50`, two application tasks, Postgres and the edge: the host used about 1.5 GB with 340 MiB available and 233 MiB of swap,
  steady; Keycloak peaked at 541 MiB (463 MiB of it anonymous memory) over its first start and a token request, with no swap and no memory events. **512 MiB would not do**:
  Keycloak's own documentation puts the least that gives its heap the old 512 MB maximum at 750 MB. Prometheus (`OBSERVABILITY=1`) and the backups overlay were not tried
  there and are unlikely to fit; use a `t4g.large` (8 GB) for them. `bootstrap.sh` adds a 2 GB swap file and writes the two Keycloak settings.
- **Not measured on Graviton or Ampere:** the figures in [Performance](INTERNALS.md#performance) are from an x86-64 desktop. A `t4g` has burstable CPU: a long load test spends its credits.

## To decide

| Decision | Options |
|---|---|
| Provider | AWS (decided 2026-10-11): `t4g.medium`, `us-east-2`, with the free plan's credits (Oracle's Always Free Ampere A1 had no capacity). Another arm64 VM works; an amd64 one needs the image built there |
| Host name | a domain or subdomain you control; the token `iss` must equal the public issuer URL |
| Who can create links | decided: a client with an invitation from the operator ([ADR 0039](adr/0039-clients-register-themselves-with-an-invitation.md)). The demo client's key is not published |
| Certificate | decided: `certbot --webroot` through the edge, with the secrets named after their contents ([DEPLOY.md](DEPLOY.md#certificate)). Written, not run against Let's Encrypt |

## Status

Built: the Keycloak overlay, the edge route for `/realms`, the production realm (`./gradlew productionRealm`: `demo-client` and
`admin-client`, no users), and backups with a restore test ([DEPLOY.md](DEPLOY.md#backups-and-a-replica)), with the repository on the
VM's disk.

Verified on 2026-10-11, on a `t4g.small`:

- **Provisioning.** `deploy/aws/launch.sh`, `bootstrap.sh` and `deploy/stack/certificate.sh` with the ACME overlay produce a VM that serves a
  Let's Encrypt certificate from the edge. The free plan refuses a `t4g.medium`; `ec2 describe-instance-types --filters
  Name=free-tier-eligible,Values=true` lists what it accepts: `t4g`, `t3` and `t8i` `micro` and `small`, and the x86 `c7i-flex.large` and
  `m7i-flex.large`.
- **The stack.** The signed image of 0.22.1 deploys, and `deploy.sh` verifies its signature (v0.22.0 was never published: its Release failed the
  vulnerability scan). Both tasks run in production mode. An unknown code answers `404`, a create without a token `401` with
  `WWW-Authenticate: DPoP`, and HTTP redirects to HTTPS.
- **Start-up.** Two tasks starting together on 2 vCPUs take 27 and 29 seconds, so the health check's start period is 60 s.
- **Keycloak** (768M, above). It imports the production realm and publishes its discovery document at
  `https://shortener.codes/realms/shortener/...` with the application's issuer. `demo-client` gets a DPoP token, `POST /api/short-links`
  answers `201` and the short URL answers `302`. The edge does not serve the master realm or the administration console.
- **Isolation between clients**, with a second client made for the test and deleted afterwards:
  - It gets `404` for the first client's link, an empty list, `404` to disable it, and `403` (`insufficient_scope`) to claim its code, the
    same answer as for a code that does not exist. The first client's link stays enabled and keeps redirecting.
  - The reverse holds, each client lists only its own links, and the same target gets a different code for each. Each can read and disable
    its own.
- **Backups** with pgBackRest, without the replica (`POSTGRES_HA=1` on a node without the `shortener.postgres-replica` label, so
  `postgres-replica` stays pending at `0/1`):
  - Postgres archives WAL, and a restart did not stop redirects (`302` while it was down: the redirect cache).
  - `backup init` took a full backup of the 44 MB database into 4.9 MB, `backup check` passed, and `backup restore-test` restored it into a
    throwaway container with the same links as the live database. A link created after that backup was in a restore too, once the WAL
    segment was switched.
  - The repository is a volume on the same disk, so it does not survive losing the VM.
  - The schedule (`deploy/postgres/backup.cron`, in `/etc/cron.d`) was installed and its lines run by hand in cron's bare environment, on the
    success and the failure path.

Not run: the replica, Prometheus, the first run of the schedule by cron itself, a renewal of the certificate 30 days on, a restore into the
real database, and an `admin-client` call on another client's link.

Not built:

1. The backup repository off the VM.
2. The `shortener-ui` client and web users (they wait for the [web UI](UI.md)).

## Steps

1. Make a budget alert first: `aws budgets create-budget` or the console (AWS Settings > Billing). The credits end on the date `aws freetier get-account-plan-state` prints.
2. `AWS_REGION=us-east-2 deploy/aws/launch.sh` creates the VM and prints its address. Create the DNS record for the host name.
3. On the VM: `git clone`, then `HOST=... ALLOWED_TARGET_HOSTS=... VERSION=... deploy/aws/bootstrap.sh`. It installs Docker, cosign and certbot, starts the
   Swarm, makes the secrets, builds the Keycloak and Postgres images and writes `.env`.
4. Make the production realm on your machine ([DEPLOY.md](DEPLOY.md#keycloak)) and copy `shortener-realm.production.json` to the VM.
5. `sudo deploy/stack/certificate.sh placeholder <host>`, `deploy/stack/deploy.sh <version>` (it reads the overlays from `.env`), then
   `sudo deploy/stack/certificate.sh issue <host> <email>`. Try it once with `STAGING=1`: the real service allows five failures an hour.
6. `deploy/postgres/backup init`, the cron file of [DEPLOY.md](DEPLOY.md#backups-and-a-replica), and `docker service ls`.
7. `perf/smoke.sh` against the public URL with the demo client.
8. Give invitations to the people who ask, and put the README commands next to the URL.

## Safety

Before anyone is invited:

- Set `SHORTENER_SHORTLINK_TARGETURLS_ALLOWEDHOSTS` to a short list (`example.com`, `*.example.org`). Anything else is
  refused with `400`. Never use `ALLOW_ANY_TARGET=true` on a public instance.
- Use the realm `./gradlew productionRealm` writes, never the dev one.
- Keep the default [rate limits](REFERENCE.md#tunables).
- Keep an administrator client whose key only you hold. Takedown: `PATCH /api/short-links/<code>` with `{"disabled": true}`.
- Say in the README, next to the URL, that availability is not promised.
- Store nothing you would mind losing: one disk, one machine.

Known limits: rate limits and the DPoP replay cache are per task, the VM is a single point of failure, and backups stay on its disk until the repository leaves it.

## Cost and teardown

- The VM is most of the bill, plus the snapshot or off-site dump. On the free plan the bill is paid from 200 USD of credits that end on
  2027-04-11 (about 6 months). On-demand prices in `us-east-2` (checked 2026-10-11; `sa-east-1` costs about 60% more):

  | | per month |
  |---|---|
  | `t4g.medium` | 24.5 USD |
  | 30 GB `gp3` | 2.4 USD |
  | public IPv4 (the Elastic IP) | 3.7 USD |
  | **about** | **31 USD**, 185 USD in six months, plus data out |

  That is inside the credits with little to spare, and a `t4g.large` is not: 295 USD. Set the alert at 150 USD.
- Teardown: `docker stack rm shortener`, then `AWS_REGION=us-east-2 deploy/aws/launch.sh teardown` (instance, Elastic IP, security group, key
  pair) and delete the DNS record. A released Elastic IP stops costing; a stopped instance with one does not.
