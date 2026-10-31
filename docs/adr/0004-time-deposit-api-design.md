# ADR-0004: Time deposit API design

- **Status:** Proposed
- **Date:** 2026-10-31

## Context
The brief requires exactly two operations: retrieve all time deposits (`id`, `planType`, `balance`, `days`,
`withdrawals`) and update the balances of all time deposits. Resources are designed first, independent of URIs,
following the conventions in ADR-0003.

## Options considered
**Identifiers:** expose the integer primary key · public UUID (v4 random / v7 time-ordered) alongside the internal
integer key · encoded integer (Sqids/Hashids — reversible obfuscation) · UUID primary key.
**Money:** binary floating point (`double`) · decimal number · decimal string · integer minor units · money object
with currency.
**Ordering:** unspecified · unspecified with deterministic implementation · documented default order.

## Decision
### Time Deposits (collection)
- Represented as a JSON array of Time Deposit; supported operation: **read the whole collection**.
- **Order is not guaranteed** by the contract. The implementation returns a deterministic order (internal key)
  so repeated calls are stable, without promising it. Sorting may be added later as an optional parameter.

### Time Deposit / Withdrawal
- Attributes exactly as the brief; withdrawals are embedded in their time deposit (dependent entity, no
  `timeDepositId` in the representation).
- **Identifiers:** the brief requires a field named `id` but does not require it to be the database key.
  To prevent enumeration and leaking counts via sequential keys, the API `id` carries a random **UUID v4**,
  stored in a separate `publicId` column (time deposits and withdrawals). The internal Integer primary key `id`
  (required by the brief and the legacy `TimeDeposit` class) and the `timeDepositId` foreign key are never exposed.
  Mapping rule: API `id` ↔ DB `publicId`.
- **Money:** decimal **string** with exactly two fraction digits (e.g. `"10000.00"`), as common in banking APIs;
  no binary floating point on the wire. **Single currency** assumed — the brief defines none.
- **planType:** string with `x-extensible-enum` (`basic`, `student`, `premium`); case-sensitive.
- `days` is a non-negative integer.
- **Balance and withdrawals:** `balance` is the current balance, already net of past withdrawals; withdrawals are
  a history record. Assumption — the brief does not relate them, and the interest calculation ignores withdrawals.

### Endpoint: list time deposits
- `GET /time-deposits` (relative to `/api/v1`), operationId `listTimeDeposits`, tag `TimeDeposits`
  (generates `TimeDepositsApi.listTimeDeposits()`).
- Path uses the plural resource name in kebab-case.
- Responses: `200` with `TimeDeposits` (empty array if none); `500` with Problem Details. No other errors apply:
  the operation takes no input and requires no authentication. The Redocly `operation-4xx-response` warning is
  therefore accepted.
- **No authentication:** not required by the brief; declared explicitly with a top-level `security: []`, so adding
  authentication later is a deliberate contract change.

### Balance Update
The brief's "update the balances of all time deposits in the database" is read as: a client-triggered operation
that applies one round of the existing monthly interest rules (`TimeDepositCalculator.updateBalance`) to every
stored time deposit and saves the resulting balances. No input; no other attribute changes (`days` is not
incremented, withdrawals are not involved).

- **Modelled as a process resource** (a balance update run), not as a state change of the collection: it is an
  action with an outcome and is not idempotent.
- **Endpoint:** `POST /balance-updates` (relative to `/api/v1`), operationId `createBalanceUpdate`, tag
  `TimeDeposits`. Top-level rather than `/time-deposits/balance-updates`: the run is not contained in the time
  deposits (it acts on them), and nesting would collide with a future `/time-deposits/{id}`.
- **Synchronous:** the response is returned after all balances are stored.
- **Response:** `200 OK` with a `BalanceUpdate` summary (`updatedCount`, `executedAt`). Not `201 Created`: runs
  are not retrievable, so there is no `Location` to point to.
- **Idempotency:** optional `Idempotency-Key` header. A repeated request with the same key returns the original
  outcome without applying interest again; without a key, each call applies interest again (documented).
  `400` Problem Details for a malformed key; `500` for unexpected errors.

## Future extensions (not part of this contract)
The brief allows exactly two endpoints, so the following is recorded for later only — not specified in
`openapi.yaml` and not implemented.

### Get a single time deposit
- `GET /time-deposits/{id}`, operationId `getTimeDeposit`, tag `TimeDeposits`.
- Path parameter `id`: the public UUID (`format: uuid`) — never the internal key.
- Responses: `200` with `TimeDeposit` (same representation as a list item, including withdrawals);
  `400` Problem Details for a malformed UUID; `404` Problem Details if no time deposit has that id;
  `500` Problem Details.
- Fits the current design without changes: the `TimeDeposit` schema is reused, and random UUIDs make
  item URLs non-enumerable.

### Open question: how withdrawals are created
The brief stores and returns withdrawals but defines no way to create them. Candidate channels, undecided:
- **Synchronous API** (customer/teller action) — needs validation, authentication and idempotency.
- **Asynchronous event** from another system (e.g. core banking) — message consumer, duplicate handling.
- **Scheduled/batch import** — interacts with the timing of balance updates.

The read representation is the same in every case. For this exercise, sample withdrawals are seeded
(see the data model ADR).

## Consequences
- The data model needs a unique public UUID per time deposit and withdrawal (data model ADR).
- The REST adapter converts the legacy `Double` balance to a 2-decimal string; the domain is unchanged.
- Adding a currency later is a deliberate, versioned change.
- Tests must not rely on collection order.
- Idempotency keys and their outcomes must be stored (data model ADR); key check and balance update run in one
  transaction (architecture ADR).
