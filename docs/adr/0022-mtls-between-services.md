# 0022. Mutual TLS between the services

- Status: Accepted, 2026-10-14, optional and off by default. Tried with `docker compose` and under Swarm, on one machine. Not tried on more than one node
- Evidence: `deploy/stack/overlays/compose.pki-ca.yaml`, `compose.pki-db.yaml`, `compose.pki.yaml`, `deploy/stack/pki.sh`, `PeerIdentityFilter`, `ComposeStackTest`, `PkiScriptTest`, [THREAT_MODEL.md](../THREAT_MODEL.md) F-07, [0021](0021-encrypt-internal-traffic.md)

## Problem

[0021](0021-encrypt-internal-traffic.md) hides the traffic. It does not authenticate it. A container that reaches the application can call it as if it were nginx. Since [0038](0038-segment-the-stack-networks.md) the application
trusts forwarded headers only from the `ingress` subnet, so such a caller can reach the application but cannot lie to it about the client. A container on `data` still needs the database password, but nothing proves the caller is the application.

## Decision

Each link gets mutual TLS, with certificates from the stack's own CA, step-ca. `PKI_CA=1` deploys the CA and `PKI=1` turns the links on. The operator's steps are in [DEPLOY.md](../DEPLOY.md#mutual-tls-between-the-services).

| Link | How |
|---|---|
| edge to application | TLS 1.3 with the edge's certificate (`upstream.mtls.conf`). The application asks for one, and `PeerIdentityFilter` serves only `spiffe://shortener.internal/prod/edge` |
| application and migration job to Postgres | TLS 1.3 and a client certificate, whose common name `pg_ident.conf` maps to the role. `pg_hba.conf` refuses those two roles anything else, so their passwords are useless |
| Prometheus to the application | not done: the management port stays plain HTTP, which the health check needs |

- **Three levels.** A root, offline and on another machine, signs the issuing CA once a year. The issuing CA's certificate may sign for the stack's names and no others (name constraints, critical), and cannot sign another CA. The node makes the issuing CA's key and request (`pki.sh init`), the root signs it (`pki.sh sign-intermediate`), and the node installs it (`install-intermediate`).
- **The CA never holds a private key but its own.** For each identity the node makes the key and a request, and the CA signs the request (`pki.sh issue`). Its certificate says the identity (an OU, a DNS name, a `spiffe://` URI), what it is for (`extendedKeyUsage`) and that it is no CA, and carries a policy that points at the documentation.
  Four identities: `edge`, `shortener`, `migrator` (30 days) and `postgres` (a year).
- **One algorithm.** Every key is ECDSA P-256 and every certificate is signed with SHA-256. Ed25519 was tried first and does not do: a certificate it signs has no digest for SCRAM channel binding, which `libpq` uses by default, so a client could not log in to the database with a password over TLS.
- **The identity is checked in the application**, in a filter of the security chain that runs first, and not with Spring Security's `x509()`: the bearer and DPoP filters replace its result on every `/api` request, its extractors read the subject and not the URI names, and it would make `edge` the key of the per-client rate limit. The management port, which shares the chain, is not checked.
  `production` does not start with a certificate asked for and no identity named, or an identity named and no certificate asked for.
- **Rotation is a deploy.** A secret is immutable, so each is named after a hash of its contents, and `pki.sh renew` (daily, from cron) issues what is two thirds through its life and deploys with the running version, as `certificate.sh install` does. The application is replaced one instance at a time with the new one up first, the edge in a few seconds.
- **The database goes first and alone.** Replacing the database while the application's new tasks start makes them fail to connect, and Swarm rolls the application back (measured: eighteen seconds). Turning on `PKI=1` therefore deploys the database overlay first, with the application as it runs (`compose.pki-db.yaml`, `deploy.sh`), and the rest when it is up, and `renew` renews the database's certificate alone and deploys.
- **Labels.** `docker secret inspect` shows each certificate's role, identity, serial, SHA-256 fingerprint, expiry and renewal date, which `pki.sh env` computes for `deploy.sh`.

## Consequences

- Good: a container on a stack network that is not the edge gets a `403` from the application, and no connection to the database without a certificate. The migration job's and the database's certificates, which the same CA signs, do not open the application (`PeerIdentityFilterTest`, and tried against the running stack).
- Good: the CA's key is encrypted, the root's key never reaches the node, and a CA that fails mid-issue leaves the certificate it had.
- Cost: a CA to run (256 MB), a node labelled for it, and a yearly ceremony with the root. A CA that is lost or whose certificate expires stops renewals, and a certificate that expires stops its service: `pki.sh inspect` counts the days.
- Cost: the keys of the roles are files in `secrets/`, not encrypted, like `tls_key`: root on the node has them and the CA's passwords.
- Cost: the passwords of the application and the migration job are still created, since the base stack asks for them. `pg_hba.conf` makes them useless.
- Limit: not with `POSTGRES_HA=1`, which has another image and entrypoint, and for the stack's own Postgres: a managed database has its own way.
- Limit: the CA logs the certificate and the one-time token of each request. Keep that log with the other sensitive ones.
- Limit: `docker compose` gives no service its own copy of a secret with an owner and mode, so the rehearsal (`prepare-pki.sh`) makes the files readable instead. pgjdbc refuses a key file that others can read.
- Cost: turning it on has about a minute in which the application as it was cannot write to the database, which now takes certificates only. Reads come from the cache. Renewing the database's certificate is a few seconds without the database.
- Not verified: more than one node, and the cost of TLS between the edge and the application under load (the edge keeps connections and reuses TLS sessions, which a load test would show).

## Rejected

- A service mesh: a new platform for a stack of four services.
- Vault PKI: one more system to run, for the same four certificates.
- Pinning the edge's certificate in the application: a renewal would need both services replaced in the right order. The identity check survives renewals.
- Spring Security's `x509()`: see above.
- Ed25519 for the keys, and a hybrid post-quantum key exchange on Postgres: the first does not work with SCRAM over TLS, and the second is not needed.
- Key and request made in the CA: it would see every private key.
- One deploy to turn it on: tried, and the application is rolled back.
