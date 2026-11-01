# ADR-0013: Interest periods

- **Status:** Accepted; partly superseded by [ADR-0014](0014-replay-completed-month-without-lock.md)
- **Date:** 2026-11-01

## Context
The brief asks for an endpoint that "updates the balances of all time deposits" with **monthly** interest, and for
`TimeDepositCalculator.updateBalance` to keep its behaviour: each call applies one month to the balances it is given.
It says nothing about how often the balance update may run. ADR-0004 read that silence as "each call applies interest
again" and left the cadence to the caller; an optional client `Idempotency-Key`, kept 24 hours (ADR-0012), protects
only retries of one request.

That reading lets one month of interest be applied twice in the same month — by a retry after the key expired, a
second operator, a different key or a misconfigured job. Where the brief is silent, the system is responsible for the
integrity of its data and business rules: "monthly interest" read in good faith means **at most once per month**.
The API is not released yet, so changing ADR-0004's reading breaks no client.

**The client key under such a rule.** An idempotency key is worth keeping only if it stops a request before it
engages deeper with a system that is already processing, or has processed, the same request. Under a month rule it
does not: a keyed request checks its key table and *then* the month — both short database round trips that stop
before the deposits — so the key never stops a request earlier than the month check alone, and it adds work for
every keyed request. Within a month every request already gets the same answer (the month's outcome, or `409` while
it runs), with or without a key, expired or not. What would stop a duplicate before the database is an in-memory
check, which works for the month as well (planned decision *Request protection*, `docs/adr/README.md`).

Constraints: `TimeDeposit` and `updateBalance` stay unchanged (hard constraints) — the guard decides *whether* a run
happens, never how interest is computed; exactly two endpoints (ADR-0005); runs stay serialised, synchronous and
all-or-nothing (ADR-0012).

## Options considered
**A. What "a month" is**
- **A1. UTC calendar month** of the moment the run is reserved — the server clock is UTC already (`executedAt`).
- **A2. Calendar month in a bank time zone** — closer to a real bank, but the brief names no zone or bank.
- **A3. Rolling 30 days since the last run** — no calendar; drifts and allows two runs in one calendar month.

**B. A second request in the same month**
- **B1. `200` replaying that month's stored outcome** — every request of a month gets the same answer; retries are
  harmless.
- **B2. `409` "already applied for this month"** — explicit, but every client must handle it as a normal case.

**C. The client `Idempotency-Key`**
- **C1. Keep it next to the month guard** — no contract removal, but a second mechanism doing nearly the same job
  (two tables, reservations, fences and releases); it only changes the answer to a retry across a month boundary
  within 24 hours, which under the month rule means an early run of the next month, not a duplicate.
- **C2. Remove it and reuse its mechanism as the month key** — one mechanism, one rule; a contract change before the
  first release.

**D. Where applied months are recorded**
- **D1. Replace `"idempotencyKeys"` with `"balanceUpdatePeriods"`** — the same status, token and outcome columns and
  two-state `CHECK`, but keyed by `"period"` (`DATE`, the first day of the month) instead of a client string, and never
  purged (twelve rows a year).
- **D2. Keep `"idempotencyKeys"` and store months as internal keys** — no new table, but the purge would have to skip
  them and the name would no longer say what the table holds.

**E. Missed months** (no run in October, a run in November)
- **E1. Apply the current month only** — missed months stay missed; documented.
- **E2. Apply each missed month on the next run** — closer to a ledger, but it applies interest for periods the
  brief never defines, and the plan windows would still be computed from a `days` value nobody advances.

## Decision
| Question | Chosen | Main reason |
|---|---|---|
| A. Month | **A1** UTC calendar month | Matches the server clock and `executedAt`; the brief names no zone (assumption) |
| B. Second request | **B1** `200` replay | Every request of a month gets the same answer, without new error handling (assumption) |
| C. Client key | **C2** removed, mechanism reused | It cannot stop a request earlier than the month check; one mechanism instead of two |
| D. Record | **D1** `"balanceUpdatePeriods"` | One meaning per table, named for what it holds; never purged |
| E. Missed months | **E1** current month only | No interest for undefined periods; documented as a limitation (assumption) |

**Rule:** every balance update belongs to the UTC calendar month in which it is reserved. A month is applied at most
once: while its run is in progress, other requests of that month get `409`; once it has completed, every further
request in that month gets `200` with that month's stored outcome (`processedCount`, `executedAt`). The application
derives the month from its clock with `ZoneOffset.UTC`, never from the database's time zone or `CURRENT_DATE`.

### Flow (ADR-0012's *Resulting flow*, keyed by the month)
1. **Reserve the month** in its own transaction: `INSERT … ON CONFLICT DO NOTHING` with a token (`createdAt`,
   microseconds). Inserted → reserved. Otherwise `SELECT … FOR UPDATE SKIP LOCKED`: no row (a live run holds it) →
   `409`; `COMPLETED` → `200` replay; `IN_PROGRESS` and fresh → `409`; `IN_PROGRESS` with
   `createdAt <= now − 10 minutes` → take over with a new token.
2. **Run** in one transaction: lock the month row and check the fence in ADR-0012's order (missing → `409`,
   `COMPLETED` → replay, foreign token → `409`, own token → run); lock the deposits (`ORDER BY "id"`); once the locks
   are held, read `executedAt`; apply the calculator; write and flush the balances; switch the month to `COMPLETED`
   with its outcome as the last statement, matching exactly one row. The run fences by the month it reserved and
   never re-reads the month from the clock.
3. **On any exception from step 2:** roll back and release the month (`WHERE` own token and `IN_PROGRESS`); the
   original error propagates, with a failed release attached as suppressed; a retry runs. A fence outcome (`409` or
   replay) is a result, not an exception, and releases nothing.

There is no purge: a month row is kept. Lock order: month row, then deposits by id; each run locks only its own month
row, so runs of different months cannot deadlock.

**Month boundaries:**
- A request reserved at 23:59:59 UTC on the last day of a month belongs to that month even if its run finishes after
  midnight; `executedAt` then lies in the next month.
- **Runs of different months still serialise on the deposit locks (ADR-0012, A):** a request of the new month that
  arrives while the previous month's run holds the deposits reserves its own month row and then *waits* for the
  deposits (no `409`, a different month); both months are applied, nothing is lost. If the previous month's run had
  reserved but not yet locked the deposits, the new month's run may commit first; both months are still applied
  once, and the earlier month's `executedAt` is then the later one.
- A run that stalls past midnight is never taken over by requests of the new month (they use another row); it applies
  its month when it reaches the fence, later than the month itself.
- **A past month left `IN_PROGRESS`** (its run crashed and the release failed) counts as a missed month (E1): it is
  kept, is never taken over by requests of later months, and never blocks them.
- **Clock skew** between instances shifts the month boundary by the skew; around midnight a lagging instance may
  still apply the old month after another instance applied the new one. The fence still prevents applying any one
  month twice.

**Supersedes in part:**
- ADR-0004: the run modelled as "not idempotent" and "each call applies interest again", the `Idempotency-Key`
  bullets including its `400` and its 24-hour retention.
- ADR-0006: the `"idempotencyKeys"` table and its rule that an abandoned row is "taken over or removed, never kept
  forever" (replaced by the rule for past months above).
- ADR-0010: `Idempotency-Key` as an allowed CORS request header.
- ADR-0012: the client key, its retention and purge. Its locking, reservation, liveness, takeover, fence and release
  stay, keyed by the month.

**Affected, not superseded:** ADR-0005 and ADR-0009 mention the key in passing (a preflight triggered by it; its
validation as web-slice scope); their decisions stand.

## Consequences
- **Integrity:** no sequence of requests applies a month twice; concurrent requests in a month apply it once.
- **Contract:** the `Idempotency-Key` header and its `400` are removed, and so is the sentence "Not idempotent: each
  call applies interest again"; `POST /balance-updates` describes the month rule; `200` is "this month's outcome
  (applied now or earlier)", `409` is "this month's run is in progress". No new parameter or endpoint;
  `BalanceUpdate` keeps its fields. The README's trigger instructions drop the key.
- **Schema:** a Flyway migration creates `"balanceUpdatePeriods"` (`"period"` `DATE`, the first day of the month, as
  primary key; status, token and outcome columns with the two-state `CHECK`) and drops `"idempotencyKeys"`; its header
  points to this ADR, since `V1__schema.sql` stays as applied.
- **Code:** the key reservation, fence and release are reused with the month as key; the purge and the keyed/keyless
  branching are removed, so every request takes one path.
- **Deposits added after a month's run** earn nothing until the next month; before, a client could call again.
- **Cleanup of the client key** (done in the implementation step; complete when `grep -rni idempoten` finds nothing
  outside the accepted ADRs and `V1__schema.sql`):
  - contract: the header, its `400`, the "not idempotent" sentence;
  - REST: the keyed use-case method and the header handling in the controller; the `400` mapping for constraint
    violations in `ProblemDetailsHandler`, which exists only for the key length (removed, or kept with a stated
    reason); the `Idempotency-Key` header in the CORS configuration;
  - build: `spring-boot-starter-validation`, added only for the key length (removed with the generator's bean
    validation, or kept with a new reason; a dependency change, approved separately);
  - application and persistence: `IdempotencyKeyReservations` and `IdempotencyKeyPersistenceAdapter` renamed for the
    month; key wording in `BalanceUpdateKeyState`, `BalanceUpdateResult`, `RecordBalanceUpdatePort`,
    `SaveTimeDepositsPort`, `TimeDepositJpaRepository` and `MonthlyInterestRun`;
  - tests: the key-specific cases (web slice, application, persistence, locking, end-to-end, CORS preflight, schema),
    the fake `InMemoryBalanceUpdateRecords`, `ApplyMonthlyInterestServiceTest.eachCallAppliesInterestAgain` (the old
    P3), and every `truncate … "idempotencyKeys"` in other integration tests;
  - docs: README, `docs/testing.md`, `docs/known-issues.md`, `docs/architecture/overview.md`.

  **Stays:** `TimeDepositCalculatorCharacterizationTest.eachCallAppliesInterestAgain`, which pins `updateBalance`
  itself (unchanged). Accepted ADRs change only in their status lines.
- **Missed months** are not applied later; the plan windows still depend on whoever maintains `days` (unchanged).
- **Tests** (ADR-0009): same month → replay without a second run; next month → runs; the UTC month boundary on both
  sides, including a run reserved before midnight and finished after it; runs of two months that overlap both apply;
  a past month left `IN_PROGRESS` does not block a later month; concurrent requests in one month → applied once; a
  failure releases the month so a retry runs; stale takeover at exactly 10 minutes. Scenarios P3, P6–P15, S1, S2, S6
  and R3 in `docs/testing.md` follow the month rule.
- **Not changed:** `TimeDeposit`, `updateBalance`, the interest policies (ADR-0011).
- Protection against floods and an in-memory short-circuit for duplicates are deferred: planned decision *Request
  protection* in `docs/adr/README.md`.
- The status lines of ADR-0004, ADR-0006, ADR-0010 and ADR-0012 record the partial supersession.
