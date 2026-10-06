# 0016. A hardened Compose and Swarm stack, not Kubernetes

- Status: Accepted, 2026-10-05. Supersedes the Kubernetes manifests of `962d846` and the Envoy Gateway of `ef93357`
- Evidence: `7c052cb`, `cfa1206`, `ComposeStackTest`, `docs/DEPLOY.md`, `INTERNALS.md#deployment`

## Problem

The Kubernetes setup needed four cluster operators (Envoy Gateway, cert-manager, External Secrets and the Prometheus
operator), and none of them had ever run on a real cluster. The service is two small processes and a database. The
platform had become larger than the problem, and what was never run was only validated against a schema.

## Decision

- One file, `compose.prod.yaml`, runs as `docker stack deploy` on Swarm and as `docker compose` on one host.
- Services: an nginx edge (the only one that publishes ports, in host mode so it sees client addresses), two application
  tasks, the one-shot migration job ([0013](0013-three-database-roles-and-a-migration-job.md)) and Postgres, which a
  managed database can replace.
- Every container is read-only, without capabilities, non-root, bounded in memory and CPU. Secrets are files read by
  Spring's `configtree`. The `data` network has no route out. `ComposeStackTest` fails if any of it is loosened.
- The edge replaces the forwarded headers, caps bodies at 16 KiB, sets timeouts, caps connections per address and never
  proxies the management port.
- Rollouts migrate first, start the new task before the old, and roll back a task that is unhealthy.
- Rehearsed on Docker 29.8: the 21-check smoke test through the TLS edge, and a rolling update under load with every
  request answered.

## Consequences

- Good: one file, one command, and a stack that was run, not drawn.
- Cost, stated in `INTERNALS.md`: no egress filtering by destination, no autoscaling, no `preStop` hook, secrets that
  cannot change, a database on one node with no backups, and `no-new-privileges` not applied by Swarm.
- Not verified: more than one node, real certificates, pulling from a registry, losing the database node.
- Way back: nothing in the application is Swarm-specific. A cluster can run the same image if the service ever needs one.

## Rejected

- Keeping Kubernetes: its operators and policies cannot be trusted until run, and running them was the cost being avoided.
- A managed container service: right for an instance meant to last, and costs more
  ([CLOUD.md](../CLOUD.md#the-managed-alternative)).
