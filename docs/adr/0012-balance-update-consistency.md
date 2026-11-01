# ADR-0012: Balance update consistency

- **Status:** Proposed
- **Date:** 2026-11-01

## Context
`POST /balance-updates` applies one month of interest to every stored deposit (ADR-0004). Earlier ADRs fixed what the
operation promises and handed the runtime consistency over to this ADR (ADR-0008, *Handed over*):
- **Synchronous execution** (ADR-0004, contract): the response is returned after all balances are stored; it is
  `200` with the outcome (`processedCount`, `executedAt`). There is no `202 Accepted` and no status resource.
  Everything below assumes that a run belongs to exactly one request.
- **Serialised runs** (ADR-0004, contract): "concurrent requests are processed one after another, never in
  parallel". Two overlapping runs would read the same balances, and one run's interest would be lost.
- **Idempotency** (ADR-0004, ADR-0006): with an optional `Idempotency-Key`, a repeated request replays the original
  outcome, a request still in progress gets `409 Conflict`, a failed request stores nothing, and keys are kept for
  24 hours. The key row switches to `COMPLETED` in the same transaction as the balances (ADR-0006). A row left
  `IN_PROGRESS` by a crash becomes stale and is "taken over or removed, never kept forever" (ADR-0006). The table
  allows exactly two states (`V1__schema.sql`, stricter than ADR-0006's `CHECK` formula, as its prose describes).
- **Single-deposit failures:** a balance of `10^13` or more does not fit `NUMERIC(15,2)` (ADR-0006).
- **`NaN` balances** (known issue #8, ADR-0011): PostgreSQL stores `NaN` in `NUMERIC(15,2)` despite
  `CHECK ("balance" >= 0)`, and rejects ±Infinity (pinned in `TimeDepositSchemaTest`).
- **Structure:** `@Transactional` sits on application services only (ADR-0008 rule 7); the application layer may
  use only Spring stereotype and transaction annotations (rule 5).

PostgreSQL facts this ADR relies on (PostgreSQL 18 documentation):
- Read Committed: a `SELECT … FOR UPDATE` or `UPDATE` that waits for a concurrent transaction re-evaluates its
  `WHERE` clause on the *updated* version of the row once that transaction commits, and locks and returns that
  version ([13.2.1](https://www.postgresql.org/docs/18/transaction-iso.html)).
- `INSERT … ON CONFLICT DO NOTHING` skips a row that violates a unique constraint instead of raising an error;
  inserts into a unique index may block on concurrent sessions that insert the same value
  ([INSERT](https://www.postgresql.org/docs/18/sql-insert.html)).
- A locking clause with `SKIP LOCKED` skips rows that cannot be locked immediately; `ORDER BY` with a locking clause
  may return rows out of order when the sort column is changed concurrently
  ([SELECT, locking clause](https://www.postgresql.org/docs/18/sql-select.html)).
- "PostgreSQL treats `NaN` values as equal, and greater than all non-`NaN` values"
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

**C. In-flight detection and stale `IN_PROGRESS` keys**
- **C1. Row lock as liveness, timeout and fenced takeover** — a key row locked by a running run means "in progress";
  an unlocked row older than the in-flight timeout is stale and taken over; a fencing token checked under the row
  lock keeps a stalled original and its successor from both applying interest.
- **C2. Timeout only** — a row younger than the timeout is in progress, an older one is taken over; a live run older
  than the timeout makes its duplicate wait for, or race with, the run.
- **C3. No takeover** — a crashed request blocks its key until somebody deletes the row by hand.

For the fencing token of C1:
- **C1a. The reservation's `"createdAt"`** — no schema change; after a takeover it means "reserved at".
- **C1b. A dedicated token column** (UUID or counter) — explicit, but needs a migration and a column ADR-0006 does
  not have.

**D. Removing keys**
- **D1. Purge on keyed requests** — each reservation first deletes expired `COMPLETED` rows and abandoned
  `IN_PROGRESS` rows.
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
| C. In flight / stale | **C1** row lock as liveness + fenced takeover, token **C1a** `createdAt` | C2 timeout only; C3 none; C1b token column | A duplicate gets `409` without waiting for a long run; a crash never blocks a key forever; no interest is applied twice whatever the timeout; no schema change |
| D. Cleanup | **D1** purge on keyed requests | D2 scheduler; D3 lazy only | No scheduler; deterministic tests; rows are deleted while new ones arrive |
| E. Failing deposit | **E1** abort the run | E2 skip and report | All-or-nothing, as ADR-0006 implies; no contract change; a retry is safe |
| F. `NaN` | **F1** reject in the schema | F2 application only | Cheap defense in depth: a `NaN` row would break both endpoints on read |

### Synchronous execution stays
This ADR keeps ADR-0004's synchronous operation and does not reopen it. An asynchronous variant (`202 Accepted`, the
run continues in the background) would need a way for the client to learn the outcome: a status resource such as
`GET /balance-updates/{id}` is a third endpoint, which the brief forbids (ADR-0005), and without one the client could
no longer see `processedCount` or whether the run failed. Synchronous execution also keeps the consistency simple:
a run belongs to one request and one transaction, so `409` (in progress), replay (completed) and "a failed request
stores nothing" map directly onto that transaction.

**Reconsider** when runs approach typical HTTP client timeouts (see *Growth* in Consequences) or when the run must
survive the request (e.g. scheduled monthly runs without a client). A new ADR then decides the trigger, the run
resource and how the contract changes; ADR-0004 *Future extensions* records such ideas.

### A. Row locks
The run loads the deposits with `SELECT … FROM "timeDeposits" ORDER BY "id" FOR UPDATE`, on `"timeDeposits"` alone,
without the withdrawals join of the list query. A second run waits for the first to commit and then works on the
committed balances (Read Committed, see Context), so both months of interest are kept. The isolation level stays
PostgreSQL's default, Read Committed.
- **JPA:** the adapter uses `PESSIMISTIC_WRITE`. Whether Hibernate's PostgreSQL dialect emits `FOR UPDATE` or
  `FOR NO KEY UPDATE` is **unverified**; both serialise runs. They differ for future inserts of withdrawals (a
  foreign-key check takes `KEY SHARE`, which only `FOR UPDATE` blocks), so the balance update step pins the emitted
  clause in a persistence test.
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
self-invocation pitfall). They are plain `@Service` beans, not `@UseCase`: `@UseCase` marks only implementations of
a `port.in` interface (ADR-0008 decision 6, rule 7).
- **reservations** — `purge()`, `reserve(key)` and `release(reservation)`, each in its own short transaction;
- **run** — `apply()` and `apply(reservation)`, one transaction for locks, calculation, writes and the switch to
  `COMPLETED`.

This **refines ADR-0008 decision 7** ("`@Transactional` on use-case services"): a use case that needs more than one
transaction is orchestrated by a non-transactional `@UseCase` service, and its transactional steps sit in
`application.service`. Rule 5 (only stereotype and transaction annotations in the application) and rule 7 as
enforced ("`@Transactional` appears only in `application.service`") are unchanged, and so are the ArchUnit rules.
The driven ports keep ADR-0008's purpose names (indicative; final names in the balance update step):
`LoadTimeDepositsPort` (the locking load), `SaveTimeDepositsPort` (the balances) and `RecordBalanceUpdatePort`
(reserve, fence, complete and release the key row). PostgreSQL specifics (`ON CONFLICT`, `FOR UPDATE`,
`SKIP LOCKED`) stay inside the persistence adapter (ADR-0008).

### C. In-flight detection and fenced takeover
- **Liveness is the row lock.** A run holds its key row locked for its whole transaction (fence below), and a
  reservation looks at an existing row only with `FOR UPDATE SKIP LOCKED`. A locked row is therefore a live run,
  and the duplicate gets `409` without waiting for the run's calculation and writes, however long they take. The
  one wait left: an insert of a key whose row a running transaction has *updated* but not committed waits for that
  commit. The run's switch to `COMPLETED` is therefore its **last statement**, after the balances are flushed, so
  that window is only the commit itself; the duplicate then finds `COMPLETED` and replays.
- **In-flight timeout: 10 minutes** (assumption, a named constant), only for unlocked `IN_PROGRESS` rows: such a row
  is a request between its reservation and its run, or one that crashed. It is **stale** when
  `"createdAt" <= now − 10 minutes`, and is then taken over; a younger one gets `409`. Ten minutes is far longer
  than the moment between reservation and run, and correctness does not depend on the value, because of the fence.
- **Token (C1a):** the reservation's `"createdAt"`, set by the application from its clock, truncated to microseconds
  (the precision of `timestamptz`), and compared for equality. A takeover sets a new `"createdAt"`. Tokens of one key
  strictly increase: a takeover happens only when the taking-over instance's clock is at least 10 minutes past the
  old token, so two tokens of one key cannot collide. A dedicated column (C1b) would add a migration for the same
  guarantee.
- **Fence:** the run transaction first locks the key row (`FOR UPDATE`, waiting) and decides, in this order:
  1. row missing (released or purged) → `409`, nothing changes;
  2. `COMPLETED` → replay the stored outcome (a successor completed first);
  3. `IN_PROGRESS` with a foreign token → `409`, nothing changes (a successor took over);
  4. `IN_PROGRESS` with its own token → run.

  The switch to `COMPLETED` must update exactly one row; otherwise the transaction fails and rolls back. If a request
  stalls between reservation and run for more than 10 minutes, a successor may take over: whichever of the two locks
  the row first runs, and the other gets `409` or the replay. Interest is applied once.

### D. Purge on keyed requests
Before each reservation, `purge()` runs in **its own short transaction**, committed before the reservation starts:
`DELETE FROM "idempotencyKeys" WHERE "idempotencyKey" IN (SELECT "idempotencyKey" … FOR UPDATE SKIP LOCKED)` (a
`DELETE` has no locking clause of its own, so the subquery carries it), selecting:
- `COMPLETED` rows with `"completedAt" <= now − 24 hours` (expired: the 24 hours count from completion, the moment
  from which a replay is possible);
- `IN_PROGRESS` rows with `"createdAt" <= now − 24 hours` that are not locked (abandoned: a crashed request whose
  key never came back), so that no row is kept forever (ADR-0006).

`SKIP LOCKED` keeps the purge from waiting for a run or another purge. Its own transaction keeps uncommitted
deletes out of the reservation: if two reservations purged and inserted in one transaction each, each could delete
the other's expired key and then wait on it in its insert — a deadlock. An expired key is therefore gone when it is
reused, and the request runs again; no lookup needs a separate expiry check. A purged `IN_PROGRESS` row whose
request still exists ends in the fence's "row missing" → `409`.

### E. Abort the run
All balances and the key's switch to `COMPLETED` are written in one transaction (ADR-0006, scenario S6). A deposit
that cannot be written — today only a new balance of `10^13` or more, which the column rejects (SQLState `22003`) —
fails the transaction: no balance changes, the reservation is released, and the response is the generic `500`
Problem Details (ADR-0003). It is an operator problem, not a client error, and the contract defines no other status
for it. The column constraint is the guard; the application adds no own check, so there is one source for the
limit.

### F. Reject `NaN` in the schema
A new migration adds `CHECK ("balance" <> 'NaN')` to `"timeDeposits"` and `CHECK ("amount" <> 'NaN')` to
`"withdrawals"`. Because PostgreSQL treats `NaN` as equal to `NaN` and greater than all non-`NaN` values (Context),
`<> 'NaN'` rejects exactly `NaN`, and `"amount" > 0` lets `NaN` through just as `"balance" >= 0` does; both are
derived from the documentation and verified by scenario S8 in the balance update step, which owns the write path. The
migration flips the pinned probe `acceptsANotANumberBalance` to a rejection, and its header comment points to this
ADR (`V1__schema.sql` still names the "planned" ADR, and Flyway forbids editing an applied migration). A database
that already holds a `NaN` row fails the migration instead of failing later on read.

### Resulting flow
Without a key, `apply()` runs one transaction:
1. lock all deposits (`ORDER BY "id" FOR UPDATE`); once the locks are held, read `executedAt` from the clock (UTC,
   truncated to microseconds), so serialised runs get increasing `executedAt`;
2. apply `TimeDepositCalculator.updateBalance` (ADR-0011) to the loaded deposits;
3. write each balance by internal id, rounded to cents on write (`BigDecimal.valueOf(balance).setScale(2,
   HALF_UP)`, ADR-0006, scenario S5);
4. return `processedCount` (all deposits) and `executedAt`.

With a key:
1. **`purge()`** (own transaction, D), committed.
2. **`reserve(key)`** (own transaction):
   1. `INSERT … (IN_PROGRESS, "createdAt" = token) ON CONFLICT DO NOTHING`; inserted → reserved;
   2. otherwise `SELECT … WHERE key = ? FOR UPDATE SKIP LOCKED`:
      - no row (locked by a live run, or deleted meanwhile) → **`409`**; a retry decides again;
      - `COMPLETED` → **replay** its outcome (`200`, same body);
      - `IN_PROGRESS`, not stale → **`409`**;
      - `IN_PROGRESS`, stale → take it over: `UPDATE … SET "createdAt" = <new token>` on the row this transaction
        holds locked → reserved.
3. **`apply(reservation)`** (one transaction): lock the key row and apply the fence (C); then steps 1–3 above,
   flushing the balances; then, as the last statement, switch the key to `COMPLETED` with `processedCount`,
   `executedAt` and `completedAt` (exactly one row). Commit.
4. **On any exception from step 3:** the transaction rolls back;
   `release(reservation)` deletes the row (`WHERE "createdAt" = <token> AND "status" = 'IN_PROGRESS'`) in its own
   transaction; the original error propagates (`500`), with a failure of the release added to it as suppressed. If the process dies before the
   release, the row is taken over once stale (C) or purged after 24 hours (D). Releasing is safe on any exception,
   also one before the fence decided (e.g. a database error while locking the key row): the token filter matches
   only this request's own `IN_PROGRESS` row, never a successor's or a completed one. A fence outcome (`409` or
   replay) is a result, not an exception, and releases nothing.

Lock order is the same everywhere: key row (if any), then deposits by id. A reservation takes no deposit locks and
skips locked key rows; it waits only for the commit of another reservation, purge or release transaction (short
transactions that never wait for a run; a purge never waits at all, and each reservation inserts a single key), or
for the commit of a run that has already switched its key to `COMPLETED`. No wait cycle can form, and a long run cannot stall a
reservation. The key check happens before the deposit lock, so a
duplicate request gets `409` instead of waiting behind the run it duplicates. That an `ON CONFLICT DO NOTHING`
insert does not wait for a row that is only *locked* (not updated) by a running run is **unverified** (the
documentation names blocking for concurrent changes to the same unique value); scenario P14 checks it.

## Consequences
- **Requests wait:** a run waits for a running one, without a lock or statement timeout; with a very slow run, HTTP
  clients may time out while the server continues. Each waiting request also holds a database connection from the
  bounded pool, so a few waiting runs can delay `GET /time-deposits` until the pool's own timeout. A timeout
  (`lock_timeout`, `statement_timeout`) is a follow-up once real run durations are known.
- **A failed release** (e.g. the database became unreachable) leaves the reservation behind: a retry with that key
  gets `409` until the row is stale (10 minutes). This bends "a failed request is not stored" for that window and
  is accepted.
- **A stalled original loses:** a request that stalls for more than 10 minutes between reservation and run may be
  overtaken by its duplicate and then gets `409` or the replay, although it came first.
- **Growth:** a run holds all deposits in memory and all their row locks for one transaction. That is fine at the
  kata's scale. Reconsider when a run approaches HTTP client timeouts or memory limits: batching (e.g. by id
  ranges, with a run record) gives up all-or-nothing, and asynchronous execution (see *Synchronous execution
  stays*) changes the contract; either needs a new ADR, together with the API's notion of `processedCount`.
- **Keys are global** (ADR-0006): two clients using the same key share one outcome.
- **Clock:** staleness, expiry and tokens use the application clock; a clock skew between instances shifts these
  moments by the skew, and never breaks the fence, which compares tokens for equality under a row lock.
- **The purge** deletes all expired and abandoned rows in one statement; without an index on `"completedAt"` or
  `"createdAt"` it scans the table, which stays small with a 24-hour retention. An index is a follow-up if keyed
  traffic grows.
- **Structure:** ADR-0008 decision 7 is refined as described in B; its rules and the ArchUnit tests stay as they
  are. `TimeDepositCalculator` stays the only place where interest is computed (ADR-0011).
- **Scenarios:** `docs/testing.md` P9–P14, S6 and S8 describe the tests of the balance update step.
