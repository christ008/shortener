# 0025. Keycloak in the stack, optional, behind the edge

- Status: Accepted, 2026-10-06. Builds on [0005](0005-oauth2-resource-server-with-scopes.md)
- Evidence: `deploy/stack/overlays/compose.keycloak.yaml`, `deploy/keycloak/`, `ComposeStackTest`, [THREAT_MODEL.md](../THREAT_MODEL.md) F-02, rehearsed
  on one machine with Compose

## Problem

[0005](0005-oauth2-resource-server-with-scopes.md) makes the identity provider decide who a caller is, what they may do and who owns
what. The stack had none: production expected an external one, and the only Keycloak was the development one, with `start-dev`,
`sslRequired: none`, web users and an administrator client whose key is generated per developer. A public instance, where visitors need
tokens, could not run without building the provider first, and the provider is the most trusted part of the system.

## Decision

- An optional overlay, `deploy/stack/overlays/compose.keycloak.yaml`, so an organization with its own provider takes nothing from it.
- **The image** is built from `deploy/keycloak/Dockerfile`: Keycloak built for Postgres, run as `start --optimized`, so it starts as
  it is on a read-only filesystem instead of building itself on first run. An entrypoint reads the database and administrator
  passwords from files, because Keycloak has no such convention, and unsets the variable that named them.
- **The same hardening as every service**, held by `ComposeStackTest`: read-only, no capabilities, non-root, bounded, writable memory
  only at `/tmp` and `/opt/keycloak/data/tmp`, secrets as files, nothing published.
- **A database of its own**, `keycloak`, in the stack's Postgres, with a role that only it uses. `shortener_app` cannot connect to it
  and the Keycloak role cannot connect to the shortener database, both checked in the rehearsal.
- **The edge forwards the `shortener` realm and `/resources/`, and nothing else**: not the master realm, not `/admin`. The token
  endpoint is limited to ten requests a second per address. Administering Keycloak is done from the node with `kcadm.sh`.
- **The issuer is public, the keys are fetched privately**: `KC_HOSTNAME` is the public URL, so tokens carry it as `iss`, and the
  application reads the signing keys from `http://keycloak:8080` over the stack's encrypted network.
- **A production realm**, `shortener-realm.production.template.json`, made by `tools/run Realms production` from the public keys of two
  clients: `demo-client` (create, read, delete; no custom codes) and `admin-client` (`shortlinks:admin`). No users, TLS required,
  brute-force detection, five-minute tokens, no standard flow, and events on so failed sign-ins are logged. It holds nothing
  secret, so it is a Docker config and not a secret.
- **The realm is imported once** and a bad file stops Keycloak starting, which fails closed.

## Consequences

- Good: a public instance can run end to end from this repository. The rehearsal showed a token from the stack's own provider, a
  create, `403` for a custom code and for listing another client's links, an administrator listing and disabling the link, `410`
  afterwards, the master realm refused at the edge, and `429` on the token endpoint under a burst.
- Good: nothing from development is reused. The dev realm stays development only.
- Cost: one more service, a 1 GB limit, an image to build, and about a minute to start.
- Cost: a single Keycloak. Its outage stops new tokens, not tokens already issued, which live five minutes
  ([0005](0005-oauth2-resource-server-with-scopes.md)).
- Cost: the realm is changed with `kcadm.sh` after the first start, not by editing the file.
- Not done: the image is built where the operator builds it, not by the release workflow, so it is not scanned, signed or
  accompanied by a bill of materials ([0018](0018-signed-scanned-releases.md)). Keycloak is on the `edge` network, which has a way
  out. No web client or web users yet, and Keycloak's database is not backed up.

## Rejected

- Keycloak in `start-dev` or with `--import-realm` from the development realm: a development default in production.
- Keycloak not read-only: it would be the one service that breaks the rule `ComposeStackTest` holds all the others to. A prebuilt
  image removes the need.
- Its tables in the shortener database: one compromised role would see both, and migrations of the two would share a history.
- Publishing the administration console: a login page for the most privileged account, on the internet.
