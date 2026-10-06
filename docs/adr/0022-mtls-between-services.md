# 0022. Mutual TLS between the services

- Status: **Proposed**, 2026-10-06. Nothing is built
- Evidence: [THREAT_MODEL.md](../THREAT_MODEL.md) F-07, [0021](0021-encrypt-internal-traffic.md)

## Problem

[0021](0021-encrypt-internal-traffic.md) hides the traffic. It does not authenticate it. A container that reaches the `edge`
network can call the application as if it were nginx, and the application trusts forwarded headers from any private
address. A container on `data` still needs the database password, but nothing proves the caller is the application.

## Proposal

Each link gets mutual TLS with certificates from a private CA:

| Link | How |
|---|---|
| edge to application | `proxy_ssl_certificate` in nginx, and Spring SSL bundles with `client-auth=need` on the application port |
| application to Postgres | a client certificate matched in `pg_hba.conf` with `clientcert=verify-full`, replacing the password for that role |
| Prometheus to the application | the management port with its own bundle |

## What it needs and the stack lacks

- A CA, and an issuer: step-ca or Vault PKI. cert-manager did this on Kubernetes ([0016](0016-compose-and-swarm-instead-of-kubernetes.md)).
- Rotation. Swarm secrets are immutable, so each rotation is a new secret name and a stack update. Short-lived certificates
  would need that done by a job.
- Proof that the health check, the native image and Hikari work over TLS on 8080.

## Decide when

Build it when the stack runs on more than one node, when something untrusted can attach to a stack network, or when the
database is shared. On one node with only the stack's services attached, it adds a CA and a rotation job for little.

## Rejected for now

- A service mesh: a new platform for a stack of four services.
