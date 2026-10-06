# 0009. Every error is a problem detail, rendered by Spring MVC

- Status: Accepted, 2026-10-05, started 2026-10-03
- Evidence: `4c62308`, `88ac479`, `SecurityProblemResponder`, `OpenApiContractTest`

## Problem

Errors come from two places. Domain failures happen in MVC, where an exception handler can render them. Security
failures (401, 403, 429) happen in the filter chain, before MVC, where the usual way is to write JSON by hand. Two
writers mean two shapes, and a client has to parse both.

## Decision

- Every error is RFC 9457 `application/problem+json`.
- Domain failures extend `ShortLinkException`, a thin `ErrorResponseException`, and Spring's problem-details support
  renders them. There is no `@ControllerAdvice` of ours.
- The filter chain builds a `SecurityProblem` with its headers (`WWW-Authenticate`, `Retry-After`) and hands it to MVC's
  exception resolver, so the body has the same shape and follows the client's `Accept`.
- The `401` challenge names `DPoP` with the accepted algorithms, and `Bearer` only when DPoP is not required. The error
  is `invalid_token`, `invalid_request` or `invalid_dpop_proof`.
- In production, errors and health carry no message, trace or exception name.

## Consequences

- Good: one parser for clients, and a test keeps the contract and the code in step.
- Good: failures disclose the rule that fired, not the internals. A bad host names only the host.
- Cost: security failures go through an adapter that mimics a controller's error path.

## Rejected

- A `@ControllerAdvice` plus hand-written JSON in the filters: two shapes, and ours to keep in step with Spring's.
