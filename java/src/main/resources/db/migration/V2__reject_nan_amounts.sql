-- Rejects NaN money values (ADR-0012, decision F; known issue #8). PostgreSQL stores NaN in NUMERIC(15,2) and sorts it
-- above all numbers, so NaN passes "balance" >= 0 and "amount" > 0; it treats NaN as equal to NaN, so <> 'NaN' rejects
-- exactly NaN. The infinities are already rejected by NUMERIC(15,2). V1's comment still names the "planned" Balance
-- update consistency ADR: that is ADR-0012, and an applied migration must not be edited (Flyway checksums).
-- A database that already holds a NaN row fails this migration instead of failing later on read.
alter table "timeDeposits" add check ("balance" <> 'NaN');
alter table "withdrawals" add check ("amount" <> 'NaN');
