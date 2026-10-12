# 0041. Keycloak's console is reached through an SSH tunnel, at the site's own address

- Status: Accepted, 2026-10-12. Completes [0031](0031-human-administrators-need-a-second-factor.md)
- Evidence: `deploy/edge/console.conf`, `deploy/stack/overlays/compose.console.yaml`, `ComposeStackTest`, `DeployScriptTest`, [DEPLOY.md](../DEPLOY.md#the-console-through-a-tunnel)

## Problem

The administrator's second factor is set up in Keycloak's console, and the console could not be reached. The edge forwards the `shortener` realm and nothing else,
Keycloak publishes no port, and the tunnel to the container's address that the runbook described does not work on Swarm: that address is on an overlay network the node
cannot reach (tried on the AWS VM). The host name is also fixed at the public URL, so a console that answered on another address would send the browser back to the public one.

## Decision

- **One more file of locations on the edge**, added with `CONSOLE=1` beside `KEYCLOAK=1`: `/admin/` and the master realm, at the same address as everything else.
- **For the node itself and nobody else.** The edge publishes in host mode, so a client of the Internet reaches it with its own address, and a connection made on the node, which
  is what an SSH tunnel to its port 443 is, reaches it from the gateway of `docker_gwbridge`, `172.18.0.1`. Every other address gets `404`, as for any path nothing serves.
- **The administrator names the site, not a port.** The tunnel ends on the local port 443 and a line in `/etc/hosts` points the site's name at it, so the browser uses the real
  name with the real certificate and Keycloak's redirects work as they are.
- Nothing is published that was not, and Keycloak keeps its networks and its single host name.

## Consequences

- Good: no second port, no second host name, nothing for the Internet to reach, and the console works with the setting the stack already has.
- Cost: the guard depends on the edge's host mode. Published through Swarm's ingress, every request would arrive from the gateway and the console would be open to the Internet
  (behind its password and its code). `ComposeStackTest` holds the mode and the guard of every location.
- Cost: anything running on the node can reach it. The node is trusted, and the console still asks for the password and the code.
- Cost: `172.18.0.1` is the usual gateway of `docker_gwbridge`. On a node where it is another, the console stays closed (it fails shut) until `console.conf` says the right one.
- Cost: a privileged local port, and a line in `/etc/hosts` while the tunnel is open, during which that machine reaches the site only through the tunnel.
- Tried on the AWS VM, on 2026-10-12: from the Internet `/admin/` and `/realms/master/` answer `404` and the `shortener` realm `200`; through `ssh -L` they answer `200` and the master
  realm's issuer is `https://shortener.codes/realms/master`. The nginx file was also run with the node's address replaced by the one of a test container: allowed from it, `404` from another.
  Not tried: the sign-in in a browser on the port 443, and `deploy/keycloak/admin-user`.

## Rejected

- A second port of Keycloak published on the node, with its own host name for the console (`KC_HOSTNAME_ADMIN`): one more published port, one more setting that must agree with the
  first, and no TLS on it.
- The console on the public edge for the administrator's address: an address that changes (it did, during this work), and a path the Internet can probe.
- `kcadm.sh` alone: it cannot answer a one-time code, which is the point.
