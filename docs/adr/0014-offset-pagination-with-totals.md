# 0014. Listings by page number with totals, not by cursor

- Status: Accepted, 2026-10-05. Reverts `f3ffd4a`
- Evidence: `f3ffd4a`, `51ea566`, `609f72d`, `AuthorizationIntegrationTest`, `INTERNALS.md#request-flows`

## Problem

The web UI needs a numbered pager that jumps to any page. Offset paging pays for it: Postgres produces and discards every
row before the page, and nothing bounds the page number.

## Decision

- `page`, `size` and `sort`, with `size` capped at 200. Answers carry `items`, `page`, `size`, `hasNext`, `totalItems`
  and `totalPages`.
- `hasNext` comes from fetching `size + 1` rows. The total is read from the page when it ends the listing, and counted
  only when more follows or the page is past the end. A page past the end is empty and carries the real totals.
- Sortable by `createdAt` and `shortCode`, from a whitelist of columns, with the short code as the tie-breaker so pages
  never overlap.
- Cursor paging was built and then reverted: it only moves to the next or previous page.

## Consequences

- Good: any page is one request, and a client can recover from a page past the end.
- Cost, measured: a page a million links in took 255 ms on two million links (15 ms at a hundred thousand), and a
  client allowed to list can ask for the deepest page repeatedly. The per-client limit (60 a minute) is the only bound.
- Cost: an administrator's unfiltered listing counts the whole table.
- Cost: the count is a separate statement, so a link created between the two can make them differ by one.
- Way back: keyset paging with `(created_at, short_code COLLATE "C")` served at 0.06 ms at any depth. The reverted commit
  and its tests are the starting point if the table grows large. Keep totals with an estimate.

## Rejected

- Cursors only: cheaper and stable under inserts, and the UI cannot be built on it.
