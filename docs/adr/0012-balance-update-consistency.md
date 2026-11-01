# ADR-0012: Balance update consistency

- **Status:** Proposed
- **Date:** 2026-11-01

## Context
`POST /balance-updates` applies one month of interest to every stored deposit (ADR-0004). Earlier ADRs fixed what the
operation promises and handed the runtime consistency over to this ADR (ADR-0008, *Handed over*):
- **Synchronous execution** (ADR-0004, contract): the response is returned after all balances are stored; it is
  `200` with the outcome (`processedCount`, `executedAt`). There is no `202 Accepted` and no status resource.
  Everything below assumes that a request lasts exactly as long as its run.
- **Serialised runs** (ADR-0004, contract): "concurrent requests are processed one after another, never in
  parallel". Two overlapping runs would read the same balances, and one run's interest would be lost.
- **Idempotency** (ADR-0004, ADR-0006): with an optional `Idempotency-Key`, a repeated request replays the original
  outcome, a request still in progress gets `409 Conflict`, a failed request stores nothing, and keys are kept for
  24 hours. The key row switches to `COMPLETED` in the same transaction as the balances (ADR-0006). A row left
  `IN_PROGRESS` by a crash becomes stale and is taken over or removed. The table allows exactly two states
  (`V1__schema.sql`, stricter than ADR-0006's `CHECK` formula, as its prose describes).
- **Single-deposit failures:** a balance of `10^13` or more does not fit `NUMERIC(15,2)` (ADR-0006).
- **`NaN` balances** (known issue #8, ADR-0011): PostgreSQL stores `NaN` in `NUMERIC(15,2)` despite
  `CHECK ("balance" >= 0)`, and rejects ±Infinity (pinned in `TimeDepositSchemaTest`).
- **Structure:** `@Transactional` sits on application services only (ADR-0008 rule 7); the application layer may
  use only Spring stereotype and transaction annotations (rule 5).

PostgreSQL facts this ADR relies on (PostgreSQL 18 documentation):
- Read Committed: a `SELECT … FOR UPDATE` that waits for a concurrent transaction locks and returns the *updated*
  version of the row once that transaction commits
  ([13.2.1](https://www.postgresql.org/docs/18/transaction-iso.html)).
- `INSERT … ON CONFLICT DO NOTHING` skips a row that violates a unique constraint instead of raising an error;
  inserts into a unique index may block on concurrent sessions that insert the same value
  ([INSERT](https://www.postgresql.org/docs/18/sql-insert.html)).
- `ORDER BY` with a locking clause may return rows out of order when the sort column is changed concurrently
  ([SELECT, locking clause](https://www.postgresql.org/docs/18/sql-select.html)).
- PostgreSQL treats `NaN` values as equal to each other, and greater than all other values
  ([8.1.2](https://www.postgresql.org/docs/18/datatype-numeric.html)); `timestamptz` resolves microseconds
  ([8.5](https://www.postgresql.org/docs/18/datatype-datetime.html)).

## Options considered
Six questions, each with its options; the labels are used in the decision below.

**A. Serialising runs**
- **A1. Row locks** — the run loads the deposits with `SELECT … FOR UPDATE`; a second run waits, then reads the
  committed balances. Protects every writer that locks or updates the same rows.
- **A2. Advisory lock** — `pg_advisory_xact_lock(<constant>)` at the start of a run. One lock, simple; protects only
  code that remembers to take it.
- **A3. Optimistic locking** — a `@Version` column; a concurrent run fails and must be retried. Needs a schema
  change and contradicts "processed one after another" unless the server retries.
- **A4. `SERIALIZABLE` isolation** — concurrent runs fail with serialization errors (`40001`) and need retries.
- **A5. A lock in the JVM** — breaks as soon as two application instances run.

**B. Where the reservation and its release run** (the reservation must be committed before the run, so that a
duplicate sees it and gets `409`)
- **B1. An orchestrating use case** — the use-case service has no transaction of its own and calls transactional
  components in `application.service`: reserve, run, release.
- **B2. Reservation in the adapter** — the persistence adapter reserves and releases in auto-commit, outside any
  transaction. Simpler application, but transaction boundaries hide in an adapter.
- **B3. One transaction** — the key is inserted in the run transaction; a duplicate blocks on the primary key and
  then replays. Simplest, but it never answers `409`, which ADR-0004 and the contract promise.

**C. Stale `IN_PROGRESS` keys**
- **C1. Timeout and fenced takeover** — a row older than the in-flight timeout is taken over; the reservation's
  `createdAt` is a fencing token checked under a row lock, so a slow original run and its successor can never both
  apply interest.
- **C2. Timeout without fencing** — a run that is slower than the timeout is applied twice under one key.
- **C3. No takeover** — a crashed request blocks its key until somebody deletes the row by hand.

**D. Removing keys after 24 hours**
- **D1. Purge on keyed requests** — each reservation first deletes `COMPLETED` rows older than 24 hours.
- **D2. A scheduled job** — `@Scheduled` purge; adds a scheduler and time-dependent tests.
- **D3. Lazy expiry only** — expired rows are ignored and overwritten, never deleted; the table grows forever.

**E. A deposit that cannot be written** (e.g. its new balance exceeds `NUMERIC(15,2)`)
- **E1. Abort the whole run** — one transaction; nothing changes; `500`.
- **E2. Skip and report** — other deposits are updated; the contract has no field for a partial outcome, and a
  retry would apply interest twice to the updated ones.

**F. `NaN` balances in the schema**
- **F1. Reject in the schema** — `CHECK ("balance" <> 'NaN')` and `CHECK ("amount" <> 'NaN')` in a new migration.
- **F2. Rely on the application** — `BigDecimal` cannot hold `NaN`, so the application never writes it; only manual
  SQL could.

## Decision
| Question | Chosen | Not chosen | Main reason |
|---|---|---|---|
| A. Serialising | **A1** row locks | A2, A3, A4, A5 | Waits instead of failing, as the contract says; also protects future writers of balances; no schema change |
| B. Reservation | **B1** orchestrating use case | B2 adapter; B3 one transaction | Keeps transaction boundaries in the application (rule 7); keeps the promised `409` |
| C. Stale keys | **C1** fenced takeover | C2 unfenced; C3 none | A crash never blocks a key forever, and a too-short timeout can never apply interest twice |
| D. Cleanup | **D1** purge on keyed requests | D2 scheduler; D3 lazy only | No scheduler; deterministic tests; rows are deleted while new ones arrive |
| E. Failing deposit | **E1** abort the run | E2 skip and report | All-or-nothing, as ADR-0006 implies; no contract change; a retry is safe |
| F. `NaN` | **F1** reject in the schema | F2 application only | Cheap defense in depth: a `NaN` row would break both endpoints on read |

### Synchronous execution stays
This ADR keeps ADR-0004's synchronous operation and does not reopen it. An asynchronous variant (`202 Accepted`, the
run continues in the background) would need a way for the client to learn the outcome: a status resource such as
`GET /balance-updates/{id}` is a third endpoint, which the brief forbids (ADR-0005), and without one the client could
no longer see `processedCount` or whether the run failed. Synchronous execution also keeps the consistency simple:
the request, the run and its transaction start and end together, so `409` (in progress), replay (completed) and
"a failed request stores nothing" map directly onto one transaction.

**Reconsider** when runs approach typical HTTP client timeouts (see *Growth* in Consequences) or when the run must
survive the request (e.g. scheduled monthly runs without a client). A new ADR then decides the trigger, the run
resource and how the contract changes; ADR-0004 *Future extensions* records such ideas.

### A. Row locks
The run loads the deposits with `SELECT … FROM "timeDeposits" ORDER BY "id" FOR UPDATE` (JPA
`PESSIMISTIC_WRITE`), on `"timeDeposits"` alone, without the withdrawals join of the list query. A second run waits
for the first to commit and then works on the committed balances (Read Committed, see Context), so both months of
interest are kept. The isolation level stays PostgreSQL's default, Read Committed.
- **`ORDER BY "id"`**: rows are locked in one order by every run, so two runs cannot deadlock; `"id"` is never
  updated, so the documented out-of-order caveat cannot occur.
- **Future writers** of balances (e.g. a withdrawal) must either lock the row (`FOR UPDATE`) before computing a new
  balance or update relative to the stored value (`SET "balance" = "balance" - ?`); an absolute write computed from
  an unlocked read could overwrite a run's interest.
- **Deposits inserted during a run** are not seen by it and earn interest from the next run; nothing is lost.
- No lock timeout: a waiting run waits as long as the running one takes (Consequences).

### B. Orchestrating use case
`ApplyMonthlyInterestService` (`@UseCase`, the implementation of the driving port) has **no** `@Transactional`. It
calls two transactional components in `application.service`, through their Spring proxies (different beans, so no
self-invocation pitfall):
- **reservations** — `reserve(key)` and `release(reservation)`, each in its own short transaction;
- **run** — `apply()` and `apply(reservation)`, one transaction for locks, calculation, writes and the switch to
  `COMPLETED`.

This **refines ADR-0008 decision 7** ("`@Transactional` on use-case services"): a use case that needs more than one
transaction is orchestrated by a non-transactional `@UseCase` service, and its transactional steps sit in
`application.service`. Rule 7 as enforced ("`@Transactional` appears only in `application.service`") is unchanged,
and so are the ArchUnit rules. The ports stay purpose-named in `application.port.out`; PostgreSQL specifics
(`ON CONFLICT`, `FOR UPDATE`) stay inside the persistence adapter (ADR-0008).

### C. Fenced takeover
- **In-flight timeout: 10 minutes** (assumption, a named constant). It only decides when a duplicate stops getting
  `409` and takes over; correctness does not depend on it, because of the fencing below. Ten minutes is far above
  a run at the expected data size and well beyond typical HTTP client timeouts.
- **Token:** the reservation's `"createdAt"`, set by the application from its clock, truncated to microseconds (the
  precision of `timestamptz`), and compared for equality. A takeover sets a new `"createdAt"`.
- **Fence:** the run transaction first locks the key row (`FOR UPDATE`) and proceeds only if it is still
  `IN_PROGRESS` with its own token. If the original run is merely slow, it already holds that lock: the successor
  waits, then finds `COMPLETED` and replays the stored outcome. If the successor took over before the original run
  started, the original finds a foreign token and stops without changing anything.

### D. Purge on keyed requests
The reservation transaction deletes `COMPLETED` rows whose `"completedAt"` is more than 24 hours ago before it
inserts. An expired key is therefore gone when it is reused, and the request runs again; no lookup needs a separate
expiry check. The 24 hours count from completion, the moment from which a replay is possible.

### E. Abort the run
All balances and the key's switch to `COMPLETED` are written in one transaction (ADR-0006, scenario S6). A deposit
that cannot be written — today only a new balance of `10^13` or more, which the column rejects (SQLState `22003`) —
fails the transaction: no balance changes, the reservation is released, and the response is the generic `500`
Problem Details (ADR-0003). It is an operator problem, not a client error, and the contract defines no other status
for it. The column constraint is the guard; the application adds no own check, so there is one source for the
limit.

### F. Reject `NaN` in the schema
A new migration adds `CHECK ("balance" <> 'NaN')` to `"timeDeposits"` and `CHECK ("amount" <> 'NaN')` to
`"withdrawals"` (`NaN > 0` holds in PostgreSQL as well). Because PostgreSQL treats `NaN` as equal to `NaN`, the check
rejects exactly `NaN`. It is added in the balance update step, which owns the write path, and flips the pinned
probe `acceptsANotANumberBalance` to a rejection. A database that already holds a `NaN` row fails the migration
instead of failing later on read.

### Resulting flow
Without a key, `apply()` runs one transaction:
1. lock all deposits (`ORDER BY "id" FOR UPDATE`), read `executedAt` from the clock (UTC, truncated to
   microseconds);
2. apply `TimeDepositCalculator.updateBalance` (ADR-0011) to the loaded deposits;
3. write each balance by internal id, rounded to cents on write (`BigDecimal.valueOf(balance).setScale(2,
   HALF_UP)`, ADR-0006, scenario S5);
4. return `processedCount` (all deposits) and `executedAt`.

With a key:
1. **`reserve(key)`** (own transaction): purge expired rows (D); `INSERT … (IN_PROGRESS, createdAt = token) ON
   CONFLICT DO NOTHING`. If the row was inserted → reserved. Otherwise read it:
   - `COMPLETED` → **replay** its outcome (`200`, same body);
   - `IN_PROGRESS`, not stale → **`409`**;
   - `IN_PROGRESS`, stale → take it over with `UPDATE … SET "createdAt" = <new token> WHERE "status" =
     'IN_PROGRESS' AND "createdAt" = <old token>`; one row updated → reserved, none → another request won the
     race → `409`.
2. **`apply(reservation)`** (one transaction): lock the key row and check the fence (C) — foreign token → `409`,
   `COMPLETED` → replay; then steps 1–3 above; then switch the key to `COMPLETED` with `processedCount`,
   `executedAt` and `completedAt`. Commit.
3. **On any failure in step 2:** the transaction rolls back; `release(reservation)` deletes the row
   (`WHERE "createdAt" = <token> AND "status" = 'IN_PROGRESS'`) in its own transaction; the error propagates
   (`500`). If the process dies before the release, the row becomes stale and is taken over (C).

Lock order is the same everywhere: key row (if any), then deposits by id. The key check happens before the deposit
lock, so a duplicate request gets `409` at once instead of waiting behind the run it duplicates.

## Consequences
- **Requests wait:** a run waits for a running one, without a lock or statement timeout; with a very slow run, HTTP
  clients may time out while the server continues. A timeout (`lock_timeout`, `statement_timeout`) is a follow-up
  once real run durations are known.
- **Growth:** a run holds all deposits in memory and all their row locks for one transaction. That is fine at the
  kata's scale. Reconsider when a run approaches the in-flight timeout, HTTP client timeouts or memory limits:
  batching (e.g. by id ranges, with a run record) gives up all-or-nothing, and asynchronous execution (see
  *Synchronous execution stays*) changes the contract; either needs a new ADR, together with the API's notion of
  `processedCount`.
- **Keys are global** (ADR-0006): two clients using the same key share one outcome.
- **Clock:** staleness and tokens use the application clock; a clock skew between instances shifts the takeover
  moment by the skew, and never breaks the fence.
- **The purge** deletes all expired rows in one statement; without an index on `"completedAt"` it scans the table,
  which stays small with a 24-hour retention. An index is a follow-up if keyed traffic grows.
- **Structure:** ADR-0008 decision 7 is refined as described in B; its rules and the ArchUnit tests stay as they
  are. `TimeDepositCalculator` stays the only place where interest is computed (ADR-0011).
- **Scenarios:** `docs/testing.md` P9–P14, S6 and S8 describe the tests of the balance update step.
