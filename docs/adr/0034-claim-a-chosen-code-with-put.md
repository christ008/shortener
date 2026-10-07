# 0034. A chosen code is claimed with `PUT`, and claiming again is harmless

- Status: Accepted, 2026-10-07
- Evidence: `ShortLinkWebTest`, `DefaultShortLinkServiceTest`, `AuthorizationIntegrationTest`, `OpenApiDocumentTest`, `Smoke`
- Refines [0033](0033-link-is-a-resource-and-disabling-is-a-patch.md)

## Problem

A client that chose its code sent a `POST` to the collection with a `customCode`. When the answer was lost and the client sent
it again, it got a `409`, and could not tell "I made it" from "someone else has it". The same body also decided the
permission: without the `claim` scope the request was `403` only when it carried the field.

Making the answer to a lost `POST` safe for every request was weighed too, and costs more than the problem is worth now.

## Decision

- `PUT /api/short-links/{code}` with `{"targetUrl": ...}` makes the link under the code the client chose. It needs `create` and
  `claim`, so the route decides the permission and not the body.
- Free code: `201` and a `Location`. The caller already has this link, under this code and for this target: nothing is made and
  the answer is `200` with the link as it is now, disabled or not. Any other claim on a code that exists is `409`: another
  client's link, a reserved code, or the caller's own for another target. A claim never overwrites, because a link's target never
  changes (the application's database role cannot update it).
- `POST /api/short-links` always generates the code, and a `customCode` in it is a `400`: the JSON mapper would otherwise drop the
  field, and a client still sending it would be answered with a link under a code it did not ask for.
- A repeated claim is not audited as a creation.
- Lost answers are handled by what the API already offers, and the recipe is in [OPERATING.md](../OPERATING.md#lost-answers): repeat a `PUT`
  or a `PATCH`, and look in the listing before repeating a `POST`.

## Consequences

- Good: a chosen code is created once however often the request is sent, with no state kept for it and no migration.
- Good: a client can make its own code (a random string) and so get safe retries for every link it makes.
- Cost: two ways to create, and the UI picks by whether the user typed a code.
- Cost: `PUT` usually replaces, and this one refuses to. RFC 9110 lets a server refuse a `PUT` it cannot apply, and a target that
  could be replaced is a feature nobody has asked for. If it ever can, the `409` for another target becomes the question.
- Cost: a `409` still does not say whether the code is yours. `GET /api/short-links/{code}` does, and needs `read`.
- Cost: the generated-code `POST` is still not idempotent. A client that cannot afford a stray link makes its own code.
- Incompatible with 0033's API, which was never released: `customCode` is gone from `POST`.

## Rejected

- An `Idempotency-Key` on `POST`: it covers generated codes too and is an IETF draft. It needs a table, so a migration and
  grants, and a way to expire rows, which the application role cannot do (it has no `DELETE`, [0013](0013-three-database-roles-and-a-migration-job.md)),
  so a job or a wider role. It also needs a stored fingerprint of the request and care for two requests in flight, across
  instances. Its benefit is a link nobody can reach that costs a row; nothing measured shows it is a problem. Reconsider if it does.
- A `POST` that returns the existing link for the same owner and target: it forbids two links to one target for one owner,
  which is what a campaign does.
- A `409` that carries who owns the code: it tells a stranger what a `404` hides.
