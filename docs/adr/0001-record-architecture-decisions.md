# 0001. Record architecture decisions

- Status: Accepted, 2026-10-05
- Evidence: this directory

## Problem

The reasons for a decision live in commit messages and in `INTERNALS.md`, which says how things are. A reader who asks
"why not Kubernetes?" or "why not cursors?" has to dig the answer out of Git, and a change that undoes a decision does
not know it is doing so.

## Decision

- One file per decision in `docs/adr/`, numbered, in this shape: status, problem, decision, consequences, rejected.
- A status is `Accepted`, `Superseded by NNNN` or `Proposed`. An accepted record is not edited, except to change its
  status or fix a fact. A new ADR replaces it.
- The first batch was written from the history, so each carries the date of the commit that made the decision.
- A rejected alternative that the history does not record is marked `Not in the history:`, so a reader can tell what was
  weighed at the time from what was added when writing the record.
- One page each. What the code and tests already say is linked, not copied.

## Consequences

- Good: the cost and the way back of each choice are written where a reviewer looks.
- Cost: a file to write when a decision is made, and they can go stale if nobody supersedes them.

## Rejected

- Keeping the reasons only in `INTERNALS.md`: it describes the present, so a reversed decision erases its own history.
- A wiki: it drifts from the code and is not reviewed with it.
