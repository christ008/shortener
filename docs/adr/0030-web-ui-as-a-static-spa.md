# 0030. The web UI is a static single-page app that signs requests with a browser key

- Status: **Proposed**, 2026-10-06. Nothing is built. The reasons are those [UI.md](../UI.md) gave for its choices until they were moved here
- Evidence: [UI.md](../UI.md), [0006](0006-require-dpop-bound-tokens.md), [0007](0007-owner-claim-and-ownership-rule.md), [0031](0031-human-administrators-need-a-second-factor.md), `perf/k6/mixed.js`

## Problem

People need a browser front end to create, list and disable links. The API takes only DPoP-bound tokens
([0006](0006-require-dpop-bound-tokens.md)), so a browser app must hold a key and sign every request, and a user's links must
not all belong to the UI's client ([0007](0007-owner-claim-and-ownership-rule.md)).

## Proposal

| Choice | Why |
|---|---|
| TanStack Start in SPA mode, with Router and Query | file-based type-safe routing and cached server state. SPA mode builds a static shell, so no Node server runs in production |
| Mantine | a complete set of accessible components, hooks and forms, with theming and dark mode built in |
| Authorization code with PKCE as a public client, tokens bound to a browser key (DPoP) | no client secret in the browser, and a stolen token is useless without the key. It is what DPoP was designed for |
| `oauth4webapi` | small, framework-agnostic, and implements DPoP including server nonces. `oidc-client-ts` is heavier |
| Types generated from `docs/openapi.yaml`, called through `openapi-fetch` | the contract is already tested against the code, so the UI inherits it |
| Static files in an unprivileged nginx container behind the existing gateway, under `/app` | no CORS, one origin, the same TLS and policies as the API |
| An `owner` claim of `user:<sub>` for users and the client id for service accounts | users and clients cannot collide, and the service only needs `client-id-claim=owner`. `created_by` is free text, so there is no migration |

- The key is non-extractable (WebCrypto) and stored in IndexedDB, which keeps a `CryptoKey` without exposing its bytes: page
  scripts can use it but not copy it. Tokens are never written to storage.
- The authentication module has no UI imports, so it is testable alone and another front end could reuse it.

## Cost

- A DPoP implementation in the browser to keep correct, with nonce retry and refresh. `perf/k6/mixed.js` is a working reference.
- Keycloak issuing DPoP-bound tokens to a public client through the browser flow is assumed, not verified.
- An administrator who is a person needs a second factor before the UI offers any admin view ([0031](0031-human-administrators-need-a-second-factor.md)).
- TanStack Start was a release candidate when this was written.

## Decide when

Phase 0 of [UI.md](../UI.md) is a spike that settles the Keycloak assumption before anything else is built.

## Rejected

- A backend-for-frontend that holds the tokens: a server component to run, and no DPoP in the browser. It is the fallback if the
  spike fails.
- `oidc-client-ts`: see the table.
