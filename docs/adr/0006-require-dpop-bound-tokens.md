# 0006. Require tokens bound to the client's key (DPoP)

- Status: Accepted, 2026-10-03
- Evidence: `3918d1f`, `DpopIntegrationTest`, `DpopRuntimeHints`, `SenderConstrainedBearerTokenResolver`
- Refined by [0032](0032-dpop-nonces.md): proofs also carry a nonce the server hands out

## Problem

A bearer token is a password for five minutes: whoever holds it is the client. A token in a log line, a proxy, a crash
report or a compromised dependency is a stolen identity, and nothing in the request tells the service it was not the
client who sent it.

## Decision

- Access tokens are sender-constrained (RFC 9449). The client authenticates with a signed assertion (`private_key_jwt`,
  ES256) and a DPoP proof, and gets a token whose `cnf.jkt` is the thumbprint of its DPoP key.
- Each call sends `Authorization: DPoP <token>` and a proof for that exact method, URL, token and moment, with a `jti`
  that was not used before. Spring Security checks all of it.
- The `Bearer` scheme is refused even for a valid token, because accepting it would let a stolen token be replayed as a
  plain one (RFC 9449, section 7.2).
- `shortener.security.dpop.required` is `true` by default and fixed to `true` in the production profile. Turning it off
  is for development and tests.
- The application fails to start if DPoP is required but its filter is not in the chain. A native image can leave the
  filter out silently ([0017](0017-native-image-on-a-pinned-base.md)).

## Consequences

- Good: a token alone is useless to a thief. The DPoP key can be rotated freely, since the client's identity is its
  registered assertion key, not the proof key.
- Cost: every client must be able to sign. The repository ships one, a single Java file for JDK 17 or newer
  ([0020](0020-dpop-client-in-java.md)).
- Cost: the proof's `htu` is built from the forwarded host and scheme, so a proxy that mangles them breaks every call.
- Limits: the replay cache is per instance and in memory (30 s, at most 1,000 proofs per key). Server-issued nonces are
  not supported.

## Rejected

- Plain bearer tokens with short lifetimes: smaller, and the right call for a trusted internal caller. It is the
  fallback behind the flag.
- Not in the history: mutual TLS binding (RFC 8705): needs the edge to terminate and forward client certificates, which couples the
  application to the proxy.
