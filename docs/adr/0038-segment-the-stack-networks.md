# 0038. Segment the stack's networks

- Status: Accepted, 2026-10-12
- Evidence: `deploy/stack/compose.prod.yaml`, `deploy/stack/overlays/compose.keycloak.yaml`, `ComposeStackTest`, `ForwardedHeadersTrustTest`, [THREAT_MODEL.md](../THREAT_MODEL.md) F-07, [0016](0016-compose-and-swarm-instead-of-kubernetes.md), [0021](0021-encrypt-internal-traffic.md), [0022](0022-mtls-between-services.md), [0025](0025-keycloak-in-the-stack.md)

## Problem

The stack had two networks. Everything that proxied or was proxied shared `edge`, which has a route out, so Keycloak, the most exposed
container after nginx, could reach the internet and the application's port, and the application trusted forwarded headers from every
private address (10/8, 172.16/12, 192.168/16 and more), which is all of them.

## Decision

The diagram, and what talks to what over which network, is in [INTERNALS.md](../INTERNALS.md#networks).

| Network | Members | Route out |
|---|---|---|
| `edge` | the edge | yes: Docker cannot publish a port from an internal network |
| `ingress` | the edge and the application | no |
| `idp-front` | the edge and Keycloak (overlay) | no |
| `data` | the application, the job, Postgres, Prometheus, the exporter, the replica, Keycloak | no |
| `egress` | the application | yes: an identity provider outside the stack, traces |

- The edge shares exactly one network with each service it proxies to. Docker's DNS answers with the addresses of one shared network,
  not necessarily the one meant, so a second shared network makes the edge arrive from an address the application does not trust
  (seen on a single-node swarm, 2026-10-12).
- `ingress` has a fixed subnet, `10.253.1.0/24`, written once in `compose.prod.yaml` and used both for the network and for
  `SERVER_TOMCAT_REMOTEIP_INTERNALPROXIES`. The application honours forwarded headers from there alone. An internal network has no route
  to the host, so the subnet cannot clash with the host's or a VPN's, and Docker refuses a second network with the same one.
  No variable: the value only has to be the same in two places, and `ComposeStackTest` holds it so.
- Keycloak has no route out, and nothing but the edge reaches it from the internet's side.

## Consequences

- Good: a compromised Keycloak cannot exfiltrate over the network, and cannot make the application believe a scheme, host or address
  of its choosing: it shares `data` with the application, not `ingress`, and the application ignores its forwarded headers
  (`ForwardedHeadersTrustTest`).
- Good: tested on a single-node swarm with the real files: published in host mode through internal overlays, two tasks resolved from the
  edge over `ingress` alone, the smoke test through the edge, and Keycloak with no route out.
- Cost: five networks instead of two, and a rule to keep (the edge shares one network per upstream), held by `ComposeStackTest`.
- Cost: with an identity provider outside the stack the application keeps `egress`. It has it with Keycloak too, because traces may
  go out: set `egress` to `internal: true` in a file of your own when neither is needed.
- Limit: Docker has no network policy. Within a network every member can call every other, so Keycloak still reaches the application's
  ports on `data`, and the management port (8081) is open to every network the application is on. What changed is that the application
  does not believe it. Authenticating peers is [0022](0022-mtls-between-services.md).
- Not verified: more than one node.
