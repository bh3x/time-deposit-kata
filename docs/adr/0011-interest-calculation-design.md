# ADR-0011: Interest calculation design

- **Status:** Proposed
- **Date:** 2026-11-01

## Context
The brief asks that the design be "extensible to accommodate future complexities in interest calculations" while
`TimeDepositCalculator.updateBalance(List<TimeDeposit>)` keeps its signature and behaviour (CLAUDE.md hard
constraints). Today every rule lives in one `switch` in that class: plan names, annual rates, day windows and the
30-day grace period. A new plan means editing it, against the open/closed principle. ADR-0008 reserves
`timedeposit/domain/interest` for interest policies and lets the root calculator delegate to them (named exception);
it leaves their design to this ADR.

Constraints:
- **Identical behaviour** for the existing plans, including the known defects in
  [`known-issues.md`](../known-issues.md) (binary rounding, unrounded sums, `null` handling, operation order). The
  characterization suite, the frozen legacy copy and the randomized comparison guard it (ADR-0009).
- **Only what `TimeDeposit` holds** reaches the calculator: plan type, balance, days. `TimeDeposit` does not change.
- **The domain stays framework-free** (ADR-0008 rule 5).
- **A fixed, short delivery deadline**, with the balance update endpoint still to build.

## Options considered
Five questions, each with its options; the labels are used in the decision below.

**A. What "adding a plan" means**
- **A1. Additive code** — a new plan is a new registration or a new class; existing classes stay untouched
  (open/closed). Covers any rule code can express; a new plan needs a release.
- **A2. Data-driven** — plans as database rows or configuration, changed without a release. Only for plans of a
  shape that already exists in code; needs a schema, an adapter and a rate conversion from `NUMERIC` to `double`.

**B. Policy shape**
- **B1. One class per plan** (`BasicPolicy`, `StudentPolicy`, `PremiumPolicy`) — explicit, but every rate-only plan
  needs a class.
- **B2. One parameterised policy for rate and day window**, plus own classes only for new kinds of rules — today's
  three plans share one shape.
- **B3. Enum with behaviour** — compact, but a closed set: not open for extension.

**C. Grace period** ("no interest for the first 30 days for any existing plans")
- **C1. Global in the calculator** — as today; a future plan with another grace period needs a calculator change.
- **C2. Per policy, default 30** — identical for today's plans; matches the word "existing".

**D. Policy contract**
- **D1. Unrounded `double` interest; the calculator rounds** — keeps `balance × rate / 12` in `double` and the
  single legacy rounding point.
- **D2. Rounded `BigDecimal` per policy** — a better money type, but changes the arithmetic (known issues #1, #2).

**E. Registration**
- **E1. Explicit composition in the domain** — a factory lists the plans; the `configuration` package builds the
  calculator bean.
- **E2. Spring collects `@Component` policies** — less code per plan, but puts Spring into the domain.

## Decision
| Question | Chosen | Not chosen | Main reason |
|---|---|---|---|
| A. Adding a plan | **A1** additive code | A2 data-driven — **deferred** | "Complexities" are new kinds of rules, which only code expresses; no requirement to change plans without a release; fits the deadline |
| B. Policy shape | **B2** parameterised policy + own classes for new rules | B1 one class per plan; B3 enum | Today's plans share one shape: a rate-only plan is one registration; an enum is closed |
| C. Grace period | **C2** per policy, default 30 | C1 global | Identical today; a future plan may use another grace period without touching the calculator |
| D. Policy contract | **D1** unrounded `double`, calculator rounds | D2 rounded `BigDecimal` | Behaviour stays identical by construction; one rounding point |
| E. Registration | **E1** explicit composition | E2 Spring components | Domain stays framework-free (ADR-0008); the list of plans is readable in one place |

### A. Adding a plan: A1, additive code; A2 deferred
**Why A1:**
- The brief asks for extensibility to "future complexities in interest calculations" — new kinds of rules, which
  only code can express. Data covers only plans of a shape that already exists in code.
- It is the open/closed principle the brief names (Strategy plus a registry), and testable: an extra plan can be
  registered without changing any existing class.
- Rates stay `double` literals in code, so the arithmetic is exactly the legacy one; there is no conversion step under
  a constraint that forbids any change in balances.
- Interest rules in code are reviewed and tested with every change; rows changed at runtime bypass both.
- **Speed of delivery:** the solution has a fixed, short deadline. A1 is a pure refactoring under the existing tests
  and needs no schema, adapter or new integration tests, so it can be built and verified within it; A2 would compete
  with the remaining required work (the balance update endpoint).

**Why A2 is deferred, not rejected:**
- Nothing in the brief requires changing plans without a release, frequent rate changes or an administration process.
- It would add a schema migration, a port and adapter, validation of plan rows (negative rates, overlapping windows),
  caching and refresh, and a decision on who may change rates in production and how that is audited.
- Nothing is lost: the B2 policy already has the shape a plan row would hold, so a later data source can build the
  registry without changing the calculator or the existing policies.

**Reconsider A2 when any of these occurs:**
1. Plans must be created or changed **without a release** (e.g. product or operations teams launch plans).
2. Rates change **often** or follow an external source (e.g. market or central-bank rates).
3. The plan catalogue grows to **many plans of the same shape** that differ only in their numbers, so that
   registrations in code become the bottleneck.
4. Rate changes need an **audit trail or approval workflow** outside the code review (e.g. regulatory reporting of
   who changed which rate when).
5. Plans must vary **per customer, region or currency**, so that the set of plans cannot be fixed at build time.

Then a new ADR decides the source (a table behind a `port.out` or configuration), the schema and the precision of
stored rates, validation, refresh and auditing; it builds parameterised policies from the data, while plans with their
own rule classes stay in code.

### B. Policy shape: B2, parameterised policy
`RateWindowPolicy(annualRate, gracePeriodDays, minDaysExclusive, maxDaysExclusive)` expresses all three plans:
`basic` (0.01, 30, –, –), `student` (0.03, 30, –, 366), `premium` (0.05, 30, 45, –), with open bounds where a plan has
none. A new plan with another rate or window is one registration; a new kind of rule gets its own `InterestPolicy`
class. B1 would add a class per rate-only plan; B3 cannot be extended without editing the enum.

### C. Grace period: C2, per policy
Each policy carries its grace period; today's three use 30, so the result is identical. The brief limits the 30-day
rule to "existing plans", so a future plan may define another grace period — with C1 that would mean editing the
calculator.

### D. Policy contract: D1, unrounded `double`
Policies return one month of interest, unrounded, computed as `balance × annualRate / 12` in this order in `double`.
The calculator keeps the single legacy rounding (`new BigDecimal(interest).setScale(2, HALF_UP)`) and adds the result
to the balance. D2 would change the arithmetic and the rounding (known issues #1, #2), which the brief forbids for
existing plans.

### E. Registration: E1, explicit composition
`InterestPolicies.defaults()` in the domain lists the plans; a `@Configuration` class in `configuration` builds the
calculator bean from it for the balance update use case. Policies carry no Spring annotation, which E2 would need.

### Resulting design
Types in `org.ikigaidigital.timedeposit.domain.interest` (plain Java):
- `InterestPolicy` — `double monthlyInterest(TimeDepositTerms terms)`: one month of interest, unrounded, `0.0` when
  the policy pays none (including within its grace period). Policies read the deposit only through the read-only
  `TimeDepositTerms` (ADR-0008) and never change it.
- `RateWindowPolicy` — the B2 policy above.
- `NoInterestPolicy` — pays nothing; used for unknown plan types.
- `InterestPolicies` — maps the exact, case-sensitive plan type to its policy; unknown types get `NoInterestPolicy`;
  `defaults()` registers today's three plans.

Rules that keep the legacy behaviour:
- **`null` plan type:** `InterestPolicies` returns a policy that pays nothing within the default grace period and
  throws `NullPointerException` after it (known issue #3) — kept in the registry, not as a special case in the
  calculator.
- **Calculator:** `TimeDepositCalculator` keeps its signature and its public no-argument constructor (which uses
  `InterestPolicies.defaults()`); a second constructor takes an `InterestPolicies`. It holds no plan knowledge.
- **Known defects stay** for the existing plans, as the brief requires. Fixing one for them changes balances and needs
  an ADR that supersedes this one; a future plan may use a different policy without them.
- **`NaN` balances** (known issue #8): whether PostgreSQL's `numeric` column can hold `NaN` despite
  `CHECK ("balance" >= 0)` is verified when #8 is pinned; a schema change, if needed, belongs to the *Balance update
  consistency* ADR, which owns the write path.

Tests for the refactoring (ADR-0009):
- characterization suite, frozen copy and randomized comparison stay green unchanged;
- pin known issues #8 (`NaN`) and #9 (`null` list) first, so the refactoring cannot change them silently;
- unit tests per policy on both sides of every boundary (30/31, 45/46, 365/366), unknown and differently cased plan
  types, `null` plan type within and after the grace period;
- an extensibility test that registers an extra plan without changing any existing class (test only: the brief
  defines no new plan);
- ArchUnit: `domain.interest` depends only on the JDK and `timedeposit.domain`; the calculator holds no plan types.

## Consequences
- **A new plan of the same shape** is one `RateWindowPolicy` registration; **a new kind of rule** is one new
  `InterestPolicy` class plus its registration. The calculator and existing policies stay untouched.
- **A new plan still needs a release** until data-driven plans are introduced under the conditions in A.
- **Limit:** policies see only plan type, balance and days. Rules that need withdrawals, dates or several deposits
  require a new domain entry point behind the use case — never a change to `TimeDeposit` or `updateBalance`.
- The refactoring is a pure refactoring under green tests (CLAUDE.md rule 11): a `refactor/interest-policies` step.
- [`known-issues.md`](../known-issues.md) points here for the decision on each defect.
