-- One balance update per UTC calendar month (ADR-0013). A month is stored as its first day; the CHECK guarantees it,
-- so one month can never get a second row. Rows are never purged (twelve a year). The status and outcome columns and
-- the two-state CHECK are those of the former "idempotencyKeys": IN_PROGRESS with no outcome yet (its "createdAt" is
-- the fencing token of ADR-0012), COMPLETED with the full outcome.
create table "balanceUpdatePeriods" (
    "period"         date         primary key check (extract(day from "period") = 1),
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

-- The client Idempotency-Key is removed (ADR-0013): its table goes. V1 still creates it, because an applied migration
-- must not be edited (Flyway checksums); V1's comment about the "planned" ADR refers to ADR-0012 and ADR-0013.
drop table "idempotencyKeys";
