# 0032. The server hands out DPoP nonces, and a proof must carry the current one

- Status: Accepted, 2026-10-06
- Evidence: `DpopNonces`, `DpopNonceAuthenticationConverter`, `DpopNonceFilter`, `DpopNoncesTest`, `DpopNonceIntegrationTest`,
  `DpopNoncesConfigurationTest`, `DpopClientTest`, `DpopCallsTest`. Rehearsed against Keycloak with `DpopClient.java`, `Smoke` and `perf/k6/mixed.js`.
- Refines [0006](0006-require-dpop-bound-tokens.md)

## Problem

A proof says when it was made (`iat`), and the client chooses that time. The server accepts a proof made within the last minute and
remembers its `jti` for 30 seconds, so it cannot tell a proof made just now from one made earlier and kept, or made ahead by
something that briefly held the key. RFC 9449 (section 8 and 11.1) closes that with a value the server picks: a nonce the client
must put in its proofs.

## Decision

- **The server hands out a nonce and requires it.** A request under the `DPoP` scheme whose proof lacks the current nonce is
  answered `401` with `WWW-Authenticate: DPoP error="use_dpop_nonce"` and the nonce in `DPoP-Nonce`. The client repeats the request
  once with a new proof that carries it. Every response to a DPoP request carries `DPoP-Nonce` too, so a client keeps the latest
  and needs no extra round trip until the nonce changes.
- **Nonces are stateless.** A nonce is the number of the current five-minute interval and an HMAC-SHA-256 of it, and is good for that
  interval and the next, so between five and ten minutes. Any instance with the secret checks one it did not make.
- **The secret is shared and required in production.** It is the Docker secret `dpop_nonce_secret`, read as
  `shortener.security.dpop.nonce.secret` through the config tree. The `production` profile does not start without it, as it does not
  start without a decision on targets ([0023](0023-production-must-decide-its-targets.md)), because a random secret for each
  instance would send a client from one `use_dpop_nonce` to the next. Elsewhere a blank secret is random for each process. The
  migration job serves no requests and turns nonces off.
- **Asking for a nonce is not a failed authentication.** It is counted as `dpop_nonce_requested`, at info, so the alert on failed
  authentications does not fire on it.
- **`shortener.security.dpop.nonce.enabled=false` turns it off.** Tests do, except those about nonces.
- **The clients answer it.** The reference client, the tools and the load test keep the nonce of each server and repeat once. They do
  the same for an identity provider that asks (`400` with `use_dpop_nonce`), with a new client assertion each time, since a provider
  may refuse one it has seen.

## Cost

- A client that does not handle `use_dpop_nonce` cannot call the API. This is a breaking change for one that was written before it;
  `nonce.enabled=false` is the way back.
- One extra round trip when a client has no current nonce, such as the first call of a short-lived program.
- Replacing the secret means instances with the old and the new one disagree while a deployment rolls, and clients are asked again.
- It does not make the replay cache shared: two instances still each accept one replay of a proof inside its 30 seconds.
- The nonce is read from the proof before its signature is checked, and the answer to any caller who sends a proof under the `DPoP`
  scheme is the current nonce. A nonce is not a secret, and the rest of the proof is checked as before.
- One nonce for every client, which RFC 9449 allows.

## Rejected

- Nonces kept in a cache: state for each instance, and replicas that disagree.
- A nonce for each request: a round trip for each request.
- Accepting a proof without a nonce: it would not limit a proof made ahead, which is the point.
- A nonce validator inside Spring Security's proof decoder: the DSL has no place for one, so the converter wraps
  `DPoPAuthenticationConverter` and the provider checks the rest.
