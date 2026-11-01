# ADR-0014: Replay a completed month without a lock

- **Status:** Accepted
- **Date:** 2026-11-01

## Context
ADR-0012 (C) detects a live run by its row lock: "a reservation looks at an existing row only with `FOR UPDATE SKIP
LOCKED`. A locked row is therefore a live run". ADR-0013 (flow step 1) keeps that lookup for the month row: no row
(a live run holds it) → `409`; `COMPLETED` → `200` replay.

The premise does not hold. The lookup itself takes the row lock, whatever the row's status, and keeps it until its
reservation transaction commits. Two requests that arrive together in a month that has **already completed** (a
scheduled job and an operator, or a client retrying in parallel): A's lookup locks the `COMPLETED` row; B's lookup
skips it, finds no row and answers `409` "still in progress", although nothing is in progress. That breaks
ADR-0013's rule ("once it has completed, every further request in that month gets `200`") and the contract's `409`
description. The window lasts from A's lookup to A's commit, and it opens on every replay of the month.

A `COMPLETED` row is final: completion, takeover and release match only `IN_PROGRESS` rows, and nothing deletes or
purges a month row (ADR-0013). Reading it needs no lock.

## Options considered
1. **Plain read first; the skip-locked lookup only for `IN_PROGRESS`** — after a failed insert, read the row without
   a locking clause: missing → `409`, `COMPLETED` → replay, `IN_PROGRESS` → today's `FOR UPDATE SKIP LOCKED` lookup
   with its takeover. A replay takes no row lock at all, so replays cannot make each other skip; one more port method
   and query.
2. **Plain read as a fallback** — keep the skip-locked lookup first and read without a lock only when it finds
   nothing. Also answers correctly, but every replay still locks the row briefly, which is the cause; the lock just
   stops mattering.
3. **`FOR SHARE SKIP LOCKED`** — concurrent lookups would not conflict with each other, but a takeover would have to
   upgrade its share lock to update the row, and two takeovers holding share locks can deadlock.

## Decision
**Option 1.** Only a locked `IN_PROGRESS` row means a live run. The reservation flow of ADR-0013 step 1 becomes:

1. `INSERT … ON CONFLICT DO NOTHING` with a token; inserted → reserved.
2. Otherwise read the month row **without a lock**: no row (released meanwhile) → `409`, a retry decides again;
   `COMPLETED` → `200` replay.
3. `IN_PROGRESS` → `SELECT … FOR UPDATE SKIP LOCKED` as before: no row (a live run holds it, or it was released) →
   `409`; `COMPLETED` (the run completed between the two reads) → replay; `IN_PROGRESS` and fresh → `409`;
   `IN_PROGRESS` with `createdAt <= now − 10 minutes` → take over with a new token.

The order keeps the insert first, as in ADR-0013, so a request of a new month still costs one statement.

**Remaining window (accepted):** a request that reads `IN_PROGRESS` in step 2, and finds the row in step 3 already
completed *and* locked by another transaction — a request that read it as `IN_PROGRESS` just before, or a run fenced
out by a takeover that locks the row on its way to the fence — gets `409`. The run really was in
progress when the request looked, and the next request replays. Closing it would need a further read for a window of
a commit's length.

**Supersedes in part:**
- ADR-0012 (C): "a reservation looks at an existing row only with `FOR UPDATE SKIP LOCKED`. A locked row is therefore
  a live run" — now: a reservation reads the row without a lock first, and a locked `IN_PROGRESS` row is a live run.
- ADR-0013 (flow step 1): replaced by the three steps above. Its rule, fence (step 2) and release (step 3) stand.

## Consequences
- **Correctness:** every request in a completed month gets `200` with the stored outcome, also while another
  transaction holds the row locked.
- **Port:** `RecordBalanceUpdatePort` gains `find(period)` (no lock); `findUnlocked` keeps its name and meaning (lock
  if free, skip otherwise). The query stays in the persistence adapter (ADR-0008).
- **Unchanged:** contract, schema, the fence, takeover and release, `TimeDeposit` and `updateBalance`.
- **Tests** (ADR-0009, `docs/testing.md` P16): a completed month locked by another transaction is replayed
  (application, fakes; locking IT with real `SKIP LOCKED`); a replay takes no row lock and `find` never waits for one;
  the races between the reads (row released → `409`, run completed → replay, completed and locked → `409`).
- The status lines of ADR-0012 and ADR-0013 record the partial supersession.
