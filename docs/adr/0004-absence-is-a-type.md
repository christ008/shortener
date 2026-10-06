# 0004. Absence is a type

- Status: Accepted, 2026-10-05
- Evidence: `88ac479`, `914625f`, `INTERNALS.md#domain-types`

## Problem

A nullable value says nothing about what null means: not found, not disabled, no creator, no cache? Each caller decides
alone, and a missing case is a `NullPointerException` in production, or worse, a silent default in an authorization
check.

## Decision

- Sealed types where the code controls the model: `LinkStatus`, `Actor`, `CreatedByFilter`, `LinkLookup`,
  `InsertResult`, `RateLimitDecision`, `RateLimitKey`, `AuthScheme`. `ShortLinkException` is sealed too.
- Null objects instead of an absent collaborator: `NoRedirectCache`, `AnyTarget`.
- `when` over a sealed type has no `else`, so a new case is a compile error.
- Nulls remain only where a framework owns the signature (JDBC, Caffeine's loader, the request DTO, Spring Security),
  converted at that boundary. The JSON contract still sends `null` where it always did.
- Objects are asked, not interrogated: `link.isCreatedBy(client)`, `link.requireActive()`, `shortCode.requireClaimable()`.

## Consequences

- Good: the authorization and listing rules cannot silently take a default branch, and each null object has tests for its
  contract.
- Cost: more types and a little mapping at the edges.

## Rejected

- Nullable fields with Kotlin's null checks: they stop a crash, not a wrong decision about what null meant.
