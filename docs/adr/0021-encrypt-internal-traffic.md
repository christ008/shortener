# 0021. Encrypt the stack's internal traffic

- Status: Accepted, 2026-10-06
- Evidence: `compose.prod.yaml`, `ComposeStackTest`, [THREAT_MODEL.md](../THREAT_MODEL.md) F-07

## Problem

TLS ends at the edge, so tokens and proofs travel from the edge to the application over a Docker network, and rows travel
from the application to the database. On one node that never leaves the host. On more than one it crosses the network, and
only the `data` network was encrypted.

## Decision

- Both overlay networks, `edge` and `data`, are created with `encrypted: 'true'`. On Swarm that is IPsec between nodes: the
  operator opens UDP 4789 and ESP between them. `ComposeStackTest` fails if either loses it.
- A database outside the stack is reached with `sslmode=verify-full`, as [DEPLOY.md](../DEPLOY.md#managed-database) says.
- Authenticating the peers, not only encrypting, is [0022](0022-mtls-between-services.md), proposed.

## Consequences

- Good: a passive observer between nodes sees nothing, with no change to the application.
- Cost: IPsec has CPU and MTU costs, and the ports between nodes must be open.
- Limit: the overlay encrypts, it does not say who is on the other end. Anything attached to the network can talk to the
  application, which is why 0022 exists.
- Not verified: more than one node.

## Rejected

- A check that fails startup when the database URL lacks `sslmode`: the stack's own Postgres has no TLS and would not start.
