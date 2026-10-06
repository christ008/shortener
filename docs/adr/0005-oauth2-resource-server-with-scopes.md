# 0005. An OAuth2 resource server with scopes, not API keys

- Status: Accepted, 2026-10-03. Replaces the API-key scheme of `4c62308`
- Evidence: `6e9241f`, `SecurityIntegrationTest`, `AuthorizationIntegrationTest`

## Problem

The service must know who is calling and what they may do. The first answer was API keys: random secrets stored as
SHA-256 digests in configuration and compared in constant time. That works, but every key is a long-lived bearer secret
the service holds a digest of, rotation is a deployment, and a key has no scopes.

## Decision

- The service is a resource server. Access tokens (JWT) come from an external identity provider, Keycloak as the
  reference, and are validated locally against its published keys: signature, issuer, audience `shortener-api`, expiry
  and the `at+jwt` type (RFC 9068). No call to the provider per request.
- Scopes decide the operation (`create`, `claim`, `read`, `delete`, `admin`). Their names are configuration. Checks are
  method-security annotations on the service.
- Deny by default: `/api/**` needs a token, following a link and the health probes are public, everything else is denied.
- Only the `Authorization` header carries a token, never a query string or a form body.

## Consequences

- Good: the service keeps no credentials of its clients, tokens expire in five minutes, and any provider issuing these
  tokens works.
- Good: access control is decided in one place, on the operation, not in each controller.
- Cost: an identity provider to run, and its availability matters for new tokens (not for tokens already issued).
- Risk to watch: the key endpoint and issuer are configuration. The service does not check that they are `https`.

## Rejected

- API keys: see the problem. They remain a reasonable choice for a service with one or two trusted callers.
- Not in the history: calling the provider's introspection endpoint per request: adds a network hop and a dependency to the hot path for no
  gain over a locally validated token that lives five minutes.
