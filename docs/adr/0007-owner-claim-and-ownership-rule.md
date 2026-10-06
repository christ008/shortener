# 0007. A link belongs to the `owner` claim, and ownership is a rule on top of scopes

- Status: Accepted, 2026-10-05
- Evidence: `5a61436`, `6e9241f`, `UserOwnershipIntegrationTest`, `AuthorizationIntegrationTest`

## Problem

Scopes say what a caller may do, not to whose links. At first the owner was the token's `azp`, the client id. A web UI
gives every person a token issued to the same client, so all of them would own everything under one name.

## Decision

- The owner is the value of the claim named by `shortener.security.client-id-claim`, `owner` as shipped (`azp` is the
  code's default). The identity provider fills it with the client id for a service and with `user:<sub>` for a person, so
  the two cannot collide.
- A token without the claim is invalid: it cannot be attributed.
- Ownership is a rule over scopes. The `createdBy` and `disabledBy` arguments must be the caller, a listing filter must be
  limited to the caller, and a `PermissionEvaluator` lets a caller manage a link only if it created it or holds `admin`.
- A link the caller may not see is `404`, not `403`, through `@HandleAuthorizationDenied`, so existence is not revealed.
- `created_by` is free text, so moving to the claim needed no migration.

## Consequences

- Good: the web UI needs no change in the service, and the model holds for services and people alike.
- Cost: the identity provider must map the claim on every client, and the security of ownership is the security of that
  mapper. A mapper that copied an attribute a user can edit would let them impersonate another owner.
- The administrator is a scope, so anyone who can mint a token with `shortlinks:admin` can read and disable everything.

## Rejected

- Teams or shared ownership: no requirement yet. A later decision.
- Not in the history: owner as `azp` plus a user header: a header is not signed by the identity provider.
