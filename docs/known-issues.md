# Known issues

Defects that are known and deliberately kept. Each is pinned by a test unless marked otherwise, so it cannot change
by accident; changing it changes behaviour and needs a decision (an ADR), not a bug fix.

## Legacy interest calculation
`org.ikigaidigital.TimeDepositCalculator.updateBalance` must keep its behaviour identical (CLAUDE.md hard
constraints). Its business rules match the brief; the defects below are numeric and robustness issues. They are
preserved in the refactored class and pinned by `TimeDepositCalculatorCharacterizationTest` (plus the frozen legacy
copy and the randomized comparison). [ADR-0011](adr/0011-interest-calculation-design.md) (*Interest calculation
design*) keeps them for today's plans in the new interest policies; fixing one needs an ADR that supersedes it.

Today no production code calls the calculator: `POST /balance-updates` is still a placeholder. "Planned" mitigations
arrive with that step.

| # | Defect | Example | Pinned by | Mitigated today | Planned mitigation | Possible fix (changes balances) |
|---|---|---|---|---|---|---|
| 1 | The interest is computed and rounded in binary floating point: `new BigDecimal(double)` rounds the exact binary value, not the intended decimal, and the result also depends on the order of operations | basic `18.00`: `18 × 0.01 / 12` is meant as `0.015`, is `0.01499…` as a double, rounds to `0.01` (decimal: `0.02`); student `10.00`: meant as `0.025`, rounds to `0.02` (decimal: `0.03`); premium `3.60` gives `3.62` with the legacy order but `3.61` with `balance × (rate / 12)` | `halfCentJustBelowRoundsDown`, `halfCentJustAboveRoundsUp`, `nearTiesDependOnTheArithmeticOrder` | – | – | Compute the interest in `BigDecimal`. `BigDecimal.valueOf(double)` alone fixes only the cases whose double prints as the intended decimal (`18.00` yes, student `10.00` no: `0.024999999999999998`) |
| 2 | Only the interest is rounded; the new balance is a `double` sum and keeps binary noise | `1000.10 + 0.83` → `1000.9300000000001` | `sumKeepsDoubleNoise` | The API formats money to two decimals (ADR-0004, `MoneyFormatter`) | The POST step's write mapping rounds to cents before storing in `NUMERIC(15,2)` (ADR-0006, scenario S5) | Round the new balance to cents, or keep money in `BigDecimal` |
| 3 | `updateBalance` throws `NullPointerException` for a `null` plan type, but only after the 30-day grace period; before that it passes unnoticed | `updateBalance` on `new TimeDeposit(1, null, 1000.00, 31)` throws | `nullPlanTypeFailsAfterThirtyDays`, `nullPlanTypeIsNeverReadDuringTheFirstThirtyDays` | `"planType" NOT NULL` | – | Validate input up front, or treat `null` as an unknown plan |
| 4 | `updateBalance` always throws `NullPointerException` for a `null` balance (unboxing of the `Double`), even when no interest applies | `updateBalance` on `new TimeDeposit(1, "basic", null, 0)` throws | `nullBalanceAlwaysFails` | `"balance" NOT NULL` | – | Validate input up front |
| 5 | A negative balance gets negative interest | basic `-150.00`, 31 days → `-150.13` | `negativeBalanceGetsNegativeInterestRoundedSymmetrically` | `CHECK ("balance" >= 0)` | – | Reject negative balances, or pay no interest on them |
| 6 | Unknown plan types — including other casings, which the exact matching the brief prescribes treats as unknown — are accepted silently and earn nothing | `"Basic"`, `"STUDENT"`, `" basic"`, `"gold"` keep their balance | `UnknownPlans.noInterest` | – (no `CHECK` on `"planType"`, by design: ADR-0006) | – | Report or reject plan types without a policy |
| 7 | No atomicity across the list: when a deposit fails, the deposits before it are already updated | `[basic ok, null plan]` → the first balance is updated, then the call throws | `depositsBeforeAFailingOneAreAlreadyUpdated` | – | The POST step runs the update in one transaction (ADR-0008 rule 7; scenario S6 pending) | Validate all deposits before changing any |
| 8 | A `NaN` or infinite balance throws `NumberFormatException` when the interest is rounded (known plan after the grace period), but passes through silently for unknown plans or within the grace period | basic, `NaN`, 31 days throws; `"gold"`, `NaN` keeps `NaN` | `nonFiniteBalanceFailsWhenAKnownPlanEarns`, `nonFiniteBalancePassesThroughWithoutInterest` | Probably the read path, which maps `NUMERIC` to `BigDecimal` (unverified: PostgreSQL `numeric` can store `NaN`, and `NaN >= 0` holds there) | – | Validate input up front; whether the schema must exclude `NaN` is checked when #8 is pinned (ADR-0011) |
| 9 | `updateBalance(null)` throws `NullPointerException` | `updateBalance(null)` | `nullListFails` | – | The POST step passes the list it loaded, never `null` | Reject `null` explicitly |
| 10 | A `null` element in the list throws `NullPointerException` (when its days are read); deposits before it are already updated (#7) | `updateBalance(Arrays.asList(deposit, null))` | `nullElementFails`, `nullElementFailsAfterTheDepositsBeforeItAreUpdated` (exception type only, ADR-0011) | – | The POST step passes the deposits it loaded, never `null` | Reject `null` elements explicitly |

Already fixed by the readability refactoring (no behaviour change): the indexed loop (`xs.get(i)`, O(n²) on a
`LinkedList`) is a for-each loop; the misleading `interest +=` is a single assignment per plan.

**Readings, not defects** (recorded in code comments and ADRs):
- "No interest after 1 year" is `days < 366`: day 365 still earns interest; leap years are ignored.
- "1% interest" is a yearly rate applied monthly (`/ 12`).
- Each call applies one month of interest, whatever `days` says; calling twice applies it twice. The API guards
  repeated requests with an optional `Idempotency-Key` (ADR-0004).
- The calculator never advances `days`; the plan-period rules (grace period, student year, premium start) depend
  on whoever maintains `days` (ADR-0004: the API does not advance it either).
