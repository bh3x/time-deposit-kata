-- Demo data per ADR-0006, applied only with the demo profile. A repeatable migration runs after the versioned schema
-- migrations and again whenever this file changes, so every insert is idempotent: fixed public ids and
-- ON CONFLICT ("publicId") DO NOTHING. DO NOTHING also means an edited row never reaches an already seeded database
-- (balances there may have been updated by balance update runs); a changed case needs a new public id.
-- Withdrawals find their deposit by public id, never by the generated key.
-- Balances are current balances, already net of past withdrawals (ADR-0004). Withdrawal dates are illustrative:
-- "days" has no reference date (ADR-0004), so they are placed on long-running deposits, within the deposit's age
-- counted back from 2026-10-31.

insert into "timeDeposits" ("publicId", "planType", "days", "balance") values
    ('00000000-0000-4000-8000-000000000001', 'basic',   30,  1000.00),       -- basic: last day without interest
    ('00000000-0000-4000-8000-000000000002', 'basic',   31,  1000.00),       -- basic: first day with interest
    ('00000000-0000-4000-8000-000000000003', 'student', 30,  500.00),        -- student: last day without interest
    ('00000000-0000-4000-8000-000000000004', 'student', 31,  500.00),        -- student: first day with interest
    ('00000000-0000-4000-8000-000000000005', 'student', 365, 2000.00),       -- student: last day with interest
    ('00000000-0000-4000-8000-000000000006', 'student', 366, 2000.00),       -- student: after one year, none
    ('00000000-0000-4000-8000-000000000007', 'premium', 45,  10000.00),      -- premium: last day without interest
    ('00000000-0000-4000-8000-000000000008', 'premium', 46,  10000.00),      -- premium: first day with interest
    ('00000000-0000-4000-8000-000000000009', 'basic',   0,   250.00),        -- a new deposit
    ('00000000-0000-4000-8000-000000000010', 'gold',    120, 1000.00),       -- unknown plan: no interest
    ('00000000-0000-4000-8000-000000000011', 'Basic',   120, 1000.00),       -- wrong case: no interest
    ('00000000-0000-4000-8000-000000000012', 'premium', 200, 0.00),          -- zero balance
    ('00000000-0000-4000-8000-000000000013', 'basic',   400, 1000000000.00), -- large balance, room for interest
    ('00000000-0000-4000-8000-000000000014', 'basic',   31,  6.00),          -- interest 0.005.. rounds up to 0.01
    ('00000000-0000-4000-8000-000000000015', 'basic',   31,  18.00)          -- interest 0.0149.. rounds down to 0.01
on conflict ("publicId") do nothing;

-- Ordered by public id, so the generated internal keys follow the listed order (the list orders withdrawals by date,
-- then internal key).
insert into "withdrawals" ("publicId", "timeDepositId", "amount", "date")
select w."publicId"::uuid, d."id", w."amount"::numeric, w."date"::date
from (values
    -- deposit 5 (365 days): several withdrawals, two on the same date
    ('00000000-0000-4000-8000-000000000101', '00000000-0000-4000-8000-000000000005', '500.00', '2026-03-01'),
    ('00000000-0000-4000-8000-000000000102', '00000000-0000-4000-8000-000000000005', '250.00', '2026-03-01'),
    ('00000000-0000-4000-8000-000000000103', '00000000-0000-4000-8000-000000000005', '125.50', '2026-05-20'),
    -- deposit 6 (366 days): one withdrawal
    ('00000000-0000-4000-8000-000000000104', '00000000-0000-4000-8000-000000000006', '25.00',  '2026-02-10'),
    -- deposit 13 (400 days): several withdrawals
    ('00000000-0000-4000-8000-000000000105', '00000000-0000-4000-8000-000000000013', '100.00', '2026-01-15'),
    ('00000000-0000-4000-8000-000000000106', '00000000-0000-4000-8000-000000000013', '50.00',  '2026-06-01')
) as w ("publicId", "depositPublicId", "amount", "date")
join "timeDeposits" d on d."publicId" = w."depositPublicId"::uuid
order by w."publicId"
on conflict ("publicId") do nothing;
