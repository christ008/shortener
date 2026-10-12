# 0040. The status of a DPoP error is the one RFC 6750 and RFC 9449 give it

- Status: Accepted, 2026-10-12. Amends the errors of [0006](0006-require-dpop-bound-tokens.md) and [0009](0009-problem-details-for-every-error.md)
- Evidence: `SecurityProblemResponder`, `SingleAuthorizationFilter`, `DpopNonceAuthenticationConverter`, `SecurityProblemResponderTest`, `DpopIntegrationTest`, RFC 9449 sections 4.3, 7.1, 7.2 and 9, RFC 6750 section 3.1

## Problem

Every failure of the security chain was a `401`, whatever its error. A request with no `DPoP` proof was `401` with `invalid_request`, which RFC 6750
answers with a `400`, and `SECURITY.md` called that a deliberate deviation. Spring Security does the same (its DPoP entry point answers `401` to every
error), so the application had two answers for one request depending on which filter got there first: two `Authorization` headers were a `401`
`invalid_token` when the first was `Bearer`, and `invalid_request` when it was `DPoP`.

## Decision

| What is wrong | Status | `error` |
|---|---|---|
| no credentials | `401` | none (section 7.2 asks for no error code) |
| a bad token, a `Bearer` token where DPoP is required, a bad key binding | `401` | `invalid_token` |
| no `DPoP` proof, more than one, or one that fails section 4.3 | `401` | `invalid_dpop_proof` |
| a proof without the current nonce | `401` with `DPoP-Nonce` | `use_dpop_nonce` |
| the token presented in more than one way (two `Authorization` headers) | `400` | `invalid_request` |
| a valid token without the scope | `403` | `insufficient_scope` |

- The RFC 9449 names `401` for every challenge (section 7.1) and `400` once, for a request that uses more than one method (section 7.2, figure 19). It
  does not name a code for a missing proof, so that case follows the section that says what `invalid_dpop_proof` is for (a proof that fails the checks of
  section 4.3, among them having exactly one `DPoP` header) and keeps `invalid_request` for what is not well formed.
- `SingleAuthorizationFilter` refuses more than one `Authorization` header on `/api/` before the filters that read it, so the answer does not depend on which
  header is first.
- The authorization server's endpoint (Keycloak's) answers `400` with `use_dpop_nonce` and the client of this repository, `DpopClient`, repeats on that
  as well as on the resource's `401`: those are two servers, and each follows its own section.

## Consequences

- Good: one status for one error, and the same as another resource server would give. The deviation of `SECURITY.md` is gone.
- Cost: a client that matched `invalid_request` on a missing proof sees `invalid_dpop_proof` instead, and a `400` where it saw `401` for two `Authorization`
  headers. Neither is in the OpenAPI document as it was, which is updated.
- Limit: the choice for a missing proof is ours where the RFC is silent. A client library should treat any `401` with a `DPoP` challenge as one to read.
