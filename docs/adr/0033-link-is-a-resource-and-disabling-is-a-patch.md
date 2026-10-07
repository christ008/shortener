# 0033. A link is a resource: its `Location` is the resource, and disabling is a `PATCH`

- Status: Accepted, 2026-10-07
- Evidence: a review of the API against REST, `ShortLinkControllerTest`, `ShortLinkWebTest`, `OpenApiDocumentTest`

## Problem

Three places where the API said one thing and meant another:

- The `201` of `POST /api/short-links` answered `Location: /{code}`, the public redirect. A client that follows a
  `Location` to read what it created was sent to the target site, and the body, which had no short URL, left the
  `Location` as the only place that said what to share.
- Disabling was `DELETE`, and the link stayed: `GET` answered `200` with `disabledAt`, the code stayed taken, and it
  could not be undone. A state change carried by the verb that means removal.
- The contract said "cannot drift quietly", and a test checked only the routes, the scope names and the version. It
  had already drifted: no `400` on a malformed code, a page size described as an error where the code cuts it, and a
  disabled link described as stopping within 30 seconds where an instance that cannot reach the database serves it for
  5 minutes.

## Decision

- `Location` is `/api/short-links/{code}`. The representation has `shortUrl`, `/{code}` on the host of the request: the URL
  to share. Every link carries it, in a listing too.
- Disabling is `PATCH /api/short-links/{code}` with `{"disabled": true}` (a JSON merge patch), answered `200` with the link.
  `DELETE` is gone: there are no clients and the version is 0.x, so no alias.
- `{"disabled": false}` and a body without `disabled` are `400`. Enabling a link again is not built: it needs a rule on
  who may undo a takedown (the owner of a link an administrator took down must not), a repository operation and an audit
  event. The schema says `enum: [true]`, so allowing `false` later breaks no one.
- The scope that guards it stays `shortlinks:delete`. Renaming it touches the realm templates, the client and the tools.
- `app` is a reserved code: the gateway sends `/app/*` to the UI ([UI.md](../UI.md)).
- `OpenApiDocumentTest` reads the contract without the application: every field of a representation is a property of its
  schema and the other way round, and every operation documents the failures its inputs and its security cause. It runs
  with no Docker, so it runs on every change.

## Consequences

- Good: `Location` follows RFC 9110, and a client has one field for the URL to share.
- Good: the verb says what happens. `PATCH` leaves room for a second field without a second endpoint.
- Cost: an incompatible change, which is why it is in 0.x. Callers of `DELETE` and readers of `Location` must change.
- Cost: `shortUrl` follows the request's host, so one link read through two names has two. The edge forwards any `Host`
  ([F-08](../THREAT_MODEL.md)), so the answer is reflected to the sender and no further. A configured public base URL
  would fix both and is not built.
- Cost: the test reads the contract and the types, not the responses, so a status no test calls can still be wrong.

## Rejected

- Keeping `DELETE` as an alias for a while: no one to be kind to.
- `PUT /api/short-links/{code}/disabled`: a sub-resource for one boolean, and nowhere to put the next field.
- A `Location` that stays the short URL, with the resource in the body: the header would mean something else than the RFC.
- Renaming `shortlinks:delete` to `shortlinks:disable` now: right, and a change of the identity provider's configuration that
  has nothing to do with the HTTP shape.
