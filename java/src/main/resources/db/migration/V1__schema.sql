-- Schema per ADR-0006. Identifiers are quoted camelCase: exactly the brief's table and column names
-- (PostgreSQL would fold unquoted names to lower case).

-- Time deposits. "id" is the internal key (TimeDeposit's int id, never exposed); "publicId" is the API id (ADR-0004).
-- Money is NUMERIC(15,2): cent precision below 10^13, the bound the Double mapping relies on (ADR-0006).
-- No CHECK on "planType": the legacy calculator accepts unknown plans and pays no interest for them.
create table "timeDeposits" (
    "id"       integer generated always as identity primary key,
    "publicId" uuid          not null unique default gen_random_uuid(),
    "planType" text          not null,
    "days"     integer       not null check ("days" >= 0),
    "balance"  numeric(15,2) not null check ("balance" >= 0)
);

-- Withdrawals: history of a deposit, never deleted as a side effect (ON DELETE RESTRICT). A zero withdrawal is
-- meaningless, so "amount" > 0 is stricter than the contract's "non-negative".
create table "withdrawals" (
    "id"            integer generated always as identity primary key,
    "publicId"      uuid          not null unique default gen_random_uuid(),
    "timeDepositId" integer       not null references "timeDeposits" ("id") on delete restrict,
    "amount"        numeric(15,2) not null check ("amount" > 0),
    "date"          date          not null
);

-- The list query reads withdrawals per deposit.
create index "withdrawals_timeDepositId_idx" on "withdrawals" ("timeDepositId");

-- Idempotency keys of balance update runs (ADR-0004, ADR-0006): the domain outcome, not an HTTP response.
-- Exactly two states: IN_PROGRESS with no outcome yet, COMPLETED with the full outcome. This is ADR-0006's prose
-- ("NULL while in progress"), slightly stricter than its formula, which would allow a partial IN_PROGRESS row.
-- In-flight handling and cleanup are decided in the planned Balance update consistency ADR.
create table "idempotencyKeys" (
    "idempotencyKey" varchar(255) primary key,
    "status"         varchar(20)  not null check ("status" in ('IN_PROGRESS', 'COMPLETED')),
    "processedCount" integer      check ("processedCount" >= 0),
    "executedAt"     timestamptz,
    "createdAt"      timestamptz  not null,
    "completedAt"    timestamptz,
    check (("status" = 'COMPLETED'
            and "processedCount" is not null and "executedAt" is not null and "completedAt" is not null)
        or ("status" = 'IN_PROGRESS'
            and "processedCount" is null and "executedAt" is null and "completedAt" is null))
);
