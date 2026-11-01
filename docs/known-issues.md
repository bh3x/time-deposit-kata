# Known issues

Defects that are known and deliberately kept. Each is pinned by a test, so it cannot change by accident; changing it
changes behaviour and needs a decision (an ADR), not a bug fix.

## Legacy interest calculation
`org.ikigaidigital.TimeDepositCalculator.updateBalance` must keep its behaviour identical (CLAUDE.md hard
constraints). Its business rules match the brief; the defects below are numeric and robustness issues. They are
preserved in the refactored class and pinned by `TimeDepositCalculatorCharacterizationTest` (plus the frozen legacy
copy and the randomized comparison). Which of them the new interest policies keep or fix is decided in the planned
*Interest calculation design* ADR ([ADR index](adr/README.md#planned-decisions)).

| # | Defect | Example | Pinned by | Mitigated today by | Possible fix (changes balances) |
|---|---|---|---|---|---|
| 1 | The interest is rounded from the exact binary value of the `double` (`new BigDecimal(double)`), not its decimal value | basic, balance `18.00`: `18 × 0.01 / 12` is meant as `0.015`, is `0.01499…` as a double, rounds to `0.01` (decimal `HALF_UP`: `0.02`); balance `6.00` rounds up only because its double lies just above `0.005` | `halfCentJustBelowRoundsDown`, `halfCentJustAboveRoundsUp` | – | `BigDecimal.valueOf(double)`, or compute in `BigDecimal` |
| 2 | Only the interest is rounded; the new balance is a `double` sum and keeps binary noise | `1000.10 + 0.83` → `1000.9300000000001` | `sumKeepsDoubleNoise` | Balances are stored as `NUMERIC(15,2)` (ADR-0006), so the noise is cut on each write (scenario S5, POST step); the API formats money to two decimals (ADR-0004) | Round the new balance to cents, or keep money in `BigDecimal` |
| 3 | The result depends on the order of operations: `balance × rate / 12` and `balance × (rate / 12)` round differently near a half cent | student `10.00` → `10.02` (other order `10.03`); premium `3.60` → `3.62` (`3.61`) | `nearTiesDependOnTheArithmeticOrder` | – | Decimal arithmetic makes the order irrelevant |
| 4 | A `null` plan type throws `NullPointerException`, but only after the 30-day grace period; before that it passes unnoticed | `new TimeDeposit(1, null, 1000.00, 31)` throws | `nullPlanTypeFailsAfterThirtyDays`, `nullPlanTypeIsNeverReadDuringTheFirstThirtyDays` | `"planType" NOT NULL` | Validate input up front, or treat `null` as an unknown plan |
| 5 | A `null` balance always throws `NullPointerException` (unboxing of the `Double`), even when no interest applies | `new TimeDeposit(1, "basic", null, 0)` throws | `nullBalanceAlwaysFails` | `"balance" NOT NULL` | Validate input up front |
| 6 | A negative balance gets negative interest | basic, balance `-150.00`, 31 days → `-150.13` | `negativeBalanceGetsNegativeInterestRoundedSymmetrically` | `CHECK ("balance" >= 0)`; the contract allows only non-negative amounts | Reject negative balances, or pay no interest on them |
| 7 | Unknown or differently cased plan types silently earn nothing | `"Basic"`, `"STUDENT"`, `" basic"`, `"gold"` keep their balance | `UnknownPlans.noInterest` | – (no `CHECK` on `"planType"`, by design: ADR-0006) | Validate plan types against the known policies, or report unknown ones |
| 8 | No atomicity across the list: when a deposit fails, the deposits before it are already updated | `[basic ok, null plan]` → the first balance is updated, then the call throws | `depositsBeforeAFailingOneAreAlreadyUpdated` | The POST step runs the update in one transaction (ADR-0008), so the stored balances roll back together | Validate all deposits before changing any |
| 9 | A `null` list throws `NullPointerException` | `updateBalance(null)` | – (not pinned) | Callers pass the list they loaded; never `null` | Reject `null` explicitly |

Already fixed by the readability refactoring (no behaviour change): the indexed loop (`xs.get(i)`, O(n²) on a
`LinkedList`) is a for-each loop; the misleading `interest +=` is a single assignment per plan.

**Readings, not defects** (recorded in code comments and ADRs):
- "No interest after 1 year" is `days < 366`: day 365 still earns interest; leap years are ignored.
- "1% interest" is a yearly rate applied monthly (`/ 12`).
- Each call applies one month of interest, whatever `days` says; calling twice applies it twice. The API guards
  repeated requests with an optional `Idempotency-Key` (ADR-0004).
