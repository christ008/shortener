# 0024. Logging: security events, an audit trail and degradation notices

- Status: Accepted, 2026-10-06
- Evidence: `SecurityEvents`, `AuditTrail`, `SecurityEventsTest`, `AuditTrailTest`, `deploy/observability/alerts.yml`,
  [THREAT_MODEL.md](../THREAT_MODEL.md) F-03 and A09

## Problem

The application logged one line, at the end of a migration. A bad token, a denied call, a client that kept being limited
and an administrator disabling a stranger's link left nothing but the edge's access log and `disabled_by` in a row. An
attack would have been invisible, and a takedown could not be reconstructed.

## Decision

Three kinds of line, each on its own logger and each also a counter, so alerts read rates and the log answers who and why.

| Logger | Records | Level | Counter `type` |
|---|---|---|---|
| `uy.ct.shortener.security.events` | a `401`, a `403`, a `429` | warn | `shortener.security.events`: `unauthenticated`, `forbidden`, `rate_limited` |
| `uy.ct.shortener.audit` | a create, a disable, an administrator disabling another client's link, a listing of other clients' links | info, warn for the administrator's | `shortener.audit.events`: `created`, `disabled`, `disabled_by_admin`, `listed_others` |
| the class that notices | startup configuration, and storage that cannot serve a request | info, warn | none: the `503` rate already is one |

- Fields are ECS names as key/value pairs, so the JSON of the `production` profile carries them, nested as ECS does, with the
  request's `traceId`: `event.category`,
  `event.action`, `event.reason`, `client.ip`, `url.path`, `http.request.method`, `auth.scheme`, `owner`, `actor`,
  `shortlink.code`, `shortlink.target_host`.
- **What is never written:** a token, a proof, an `Authorization` header, a query string, the exception's message (only the
  OAuth error code), or a target's path and query. The host of a target is, because abuse is investigated by host.
- A `429` is logged once per key and minute, and counted every time. A `401` or `403` is logged every time: the address
  limit applies before authentication, so their rate is bounded.
- The storage warning is logged at most every ten seconds, with the exception's type and never the statement.
- The startup line states what an operator needs to confirm: DPoP required, the rate limits, the token type, and how many
  target hosts are allowed.
- Counters have three and four series. Nothing labels by client, address or code.
- Alerts: sustained failed authentications, denied calls and rate limiting, and a burst of administrator actions on others'
  links. Each has a promtool test.

## Consequences

- Good: an investigation starts from a line with the client, the address and the reason, and a takedown has an author.
- Good: the redirect, the hot path, logs nothing.
- Cost: client addresses are personal data and are now in the application's logs as well as the edge's. They rotate with the
  container's logs, three files of 10 MB.
- Cost: the log is local. Nothing ships it or delivers the alerts that fire.
- Limit: a listing of one's own links is not recorded. A `GET` of another client's single link is `404` for anyone but an
  administrator and is not recorded either.

## Rejected

- Logging every request in the application: the edge already does, and it would put the hot path on the log.
- A label per client on the counters: unbounded series.
- A table in Postgres for the audit: the application role cannot be given one without more privileges
  ([0013](0013-three-database-roles-and-a-migration-job.md)), and a log is what an alert and a reviewer already read.
