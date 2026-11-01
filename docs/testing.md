# Testing guide

How to test changes in this repository. The decisions and their reasons are in
[ADR-0009](adr/0009-testing-strategy.md); the architecture they refer to is in
[ADR-0008](adr/0008-hexagonal-architecture.md).

## Running the tests
| Command (in `java/`) | Runs | Needs Docker |
|---|---|---|
| `./gradlew test` | unit, web slice and architecture tests (`src/test`) | no |
| `./gradlew check` | everything, incl. integration and end-to-end tests (`src/integrationTest`) | yes |
| `./gradlew jacocoTestReport` | coverage report (`build/reports/jacoco/`): the unit suite today, both suites once `src/integrationTest` has tests | as for the suites included |

`./gradlew check` must be green for every pull request.

## Where does my test go?
| I am testing… | Write a… | Doubles | Location |
|---|---|---|---|
| a domain rule (calculator, interest policy) | unit test | none | `src/test`, package of the class |
| a use case (`…Service`) | sociable unit test through its `port.in` | in-memory fakes of `port.out` (`…/application/port/out/fake/`) | `src/test`, package of the service |
| HTTP mapping, status code, JSON, Problem Details | web slice test (`@WebMvcTest`, `MockMvcTester`) | use cases as `@MockitoBean` | `src/test`, `adapter/in/rest` package |
| schema, a query, mapping or constraint | `@DataJpaTest` on PostgreSQL | none | `src/integrationTest`, `adapter/out/persistence` package |
| the whole flow, a transaction across service and database, which routes exist | end-to-end test (`@SpringBootTest`; `RestTestClient` where HTTP matters) | none | `src/integrationTest` |
| a package dependency rule | ArchUnit rule | – | `src/test/.../architecture` |

Test each rule **once**, at the **lowest** level that can show it. Do not repeat interest rules at endpoint level:
the characterization tests pin them at the calculator.

## Writing tests
- AssertJ only. Arrange / act / assert, separated by blank lines.
- Class names: `<Subject>Test`; end-to-end: `<UseCase>EndToEndTest`. Method names are behaviour sentences in
  camelCase: `returnsNoDepositsWhenNoneExist()`.
- Group by rule with `@Nested` + `@DisplayName`; use `@ParameterizedTest` with a readable `name` for case tables.
- Derive expected values from the rules, not from the code; cover both sides of every boundary (CLAUDE.md rule 12).
- Do not assert list order except where the order is the requirement (G4); otherwise use
  `containsExactlyInAnyOrder` (ADR-0004).
- Time comes from an injected `Clock` (fixed in tests); random input uses a fixed seed; never `Thread.sleep`.
- Test data: the capability's fixture factory (e.g. `TimeDepositFixtures`) for objects; a small SQL helper with the
  quoted camelCase names (ADR-0006) for database rows. Never rely on the demo seed.
- Show a new test red before making it pass (CLAUDE.md rule 11); the red state is shown, not committed.

## Characterization suite
`TimeDepositCalculatorCharacterizationTest`, `FrozenLegacyTimeDepositCalculator` and
`TimeDepositCalculatorRandomizedComparisonTest` pin the legacy `updateBalance` behaviour permanently. Extend them
when a gap is found; never weaken, skip or delete them, and never edit the frozen copy. A failure there means the
production code changed behaviour.

## Acceptance scenarios
A step is done when all scenarios it touches pass at the stated level. The balance update scenarios P3, P6–P15, S1,
S2, S6 and S8 follow [ADR-0012](adr/0012-balance-update-consistency.md) (*Balance update consistency*) and
[ADR-0013](adr/0013-interest-periods.md) (*Interest periods*).

### `GET /api/v1/time-deposits`
| # | Scenario | Level |
|---|---|---|
| G1 | Returns every stored deposit with `id` (public UUID), `planType`, `balance`, `days`, `withdrawals` | end-to-end |
| G2 | The use case returns no deposits → `200` with `[]` | web slice |
| G3 | A deposit without withdrawals has `"withdrawals": []`, never a missing field | persistence + web slice |
| G4 | Deposits ordered by internal key, withdrawals by `date` then internal id | persistence |
| G5 | Balances as two-decimal strings, HALF_UP: `1000.9300000000001` → `"1000.93"`, `0` → `"0.00"`, `0.005` → `"0.01"`, large values (unit owns the cases; the slice checks one sample) | unit + web slice |
| G6 | No internal key and no `timeDepositId` in the response | web slice + contract validation |
| G7 | Unknown plan types are listed unchanged (e.g. `"gold"`) | web slice |

### `POST /api/v1/balance-updates`
| # | Scenario | Level |
|---|---|---|
| P1 | One month of interest is applied to all deposits and stored | end-to-end |
| P2 | `200` with `processedCount` (all deposits, incl. unchanged ones) and `executedAt` in UTC | application + web slice |
| P3 | A second request in the same UTC calendar month applies no interest and returns that month's outcome (`200`); a request in the next month runs again (ADR-0013) | application (fixed clock) |
| P4 | `days`, `publicId` and withdrawals are unchanged by a balance update | persistence |
| P5 | No deposits → `processedCount` 0 | application |
| P6 | The month's outcome is stored and replayed: the same `processedCount` and the identical `executedAt` (stored truncated to microseconds, ADR-0006); the response is re-rendered from the stored outcome | application (no second run) + persistence (outcome round trip) + end-to-end (same body) |
| P7 | The contract has no `Idempotency-Key` (ADR-0013): a request that still sends one is handled like any other request of its month | web slice |
| P8 | The UTC month boundary, with a fixed clock: a request reserved in the last microsecond of a month belongs to that month, one reserved in the first microsecond of the next month to the next month; a request reserved in the last microsecond of October whose run finishes after midnight completes the October row, creates no November row, and has `executedAt` in November (ADR-0013) | application |
| P9 | In progress → `409` without waiting: an unlocked `IN_PROGRESS` month with `createdAt` = now − 10 min + 1 µs, and a month row locked by a running run; a failed run rolls back and releases the month, so a retry runs (ADR-0012, ADR-0013) | application (fixed clock, fakes) + persistence (`SKIP LOCKED`, release) |
| P10 | Overlapping requests, forced: the test holds the deposit rows locked in its own JDBC transaction, starts the requests, waits until they block (`pg_locks`, no `Thread.sleep`), then releases. Two requests of the same month apply interest once (one runs, the other gets `409` or the replay); requests of two different months (a test clock moved across midnight) both wait and both apply, nothing lost (ADR-0012, A; ADR-0013) | end-to-end |
| P11 | One deposit whose new balance exceeds `NUMERIC(15,2)` aborts the whole run: no balance changes, the month is released, so a retry runs (and fails) again instead of replaying; the `500` Problem Details is checked as wiring only (its body is R5's) (ADR-0012, ADR-0013) | end-to-end |
| P12 | Takeover at the exact boundary, with a fixed clock: an unlocked `IN_PROGRESS` month with `createdAt` = now − 10 min is taken over and runs; a `COMPLETED` row of an earlier month is still present after later months' runs (ADR-0013) | application (fixed clock, fakes) + persistence |
| P13 | Fence on the month row, in order: a missing row → `409`, a `COMPLETED` row → replay, a foreign token → `409`, each without changing a balance; the own token runs; the switch to `COMPLETED` that matches no row fails the run (ADR-0012, ADR-0013) | persistence (fence queries) + application (branching, fakes) |
| P14 | A duplicate does not wait for a running run: the test holds the deposit rows locked; request A reserves the month, locks its row and blocks on the deposits (`pg_locks`); request B in the same month gets `409` without blocking; the test releases the locks, A completes, and B's retry gets the replayed outcome; interest is applied once (ADR-0013) | end-to-end |
| P15 | A past month left `IN_PROGRESS` (its run crashed, the release failed) does not block a request of a later month, and is never taken over by it (ADR-0013) | application (fixed clock, fakes) |

### Persistence (ADR-0006)
| # | Scenario | Level |
|---|---|---|
| S1 | Tables `timeDeposits`, `withdrawals`, `balanceUpdatePeriods` (`"period"` `DATE` as primary key) and their columns exist with the exact quoted names; `idempotencyKeys` no longer exists | persistence |
| S2 | `NOT NULL` and `CHECK` constraints reject invalid rows (negative balance or days, non-positive amount, invalid or incomplete balance update period status) | persistence |
| S3 | Deleting a deposit that has withdrawals is rejected (`ON DELETE RESTRICT`) | persistence |
| S4 | `publicId` is generated (random UUID) when omitted | persistence |
| S5 | Balances are written at cent precision: the double `1000.9300000000001` is stored as `1000.93` and read back as `1000.93` | persistence |
| S6 | The month's outcome and the balances commit or roll back together (ADR-0012, ADR-0013) | end-to-end |
| S7 | The demo seed is loaded only with the `demo` profile and covers the cases listed in ADR-0006 | persistence |
| S8 | `NaN` balances and amounts are rejected by the schema; ±Infinity already is (ADR-0012, known issue #8) | persistence |

### Both endpoints
| # | Scenario | Level |
|---|---|---|
| R1 | Only the two operations are mapped: an unknown path, `GET /error`, `/v3/api-docs`, `/swagger-ui.html`, `/actuator`, `/actuator/health` and `/index.html` → `404` Problem Details, also for near misses of the real paths (trailing slash, other case, other version) and for `OPTIONS` or a CORS preflight on an unknown path; a guard on the running context lists every handler mapping, so a route nobody probes still fails (ADR-0005) | end-to-end |
| R2 | A wrong method on a known path → `405` Problem Details (ADR-0005) | end-to-end |
| R3 | A CORS preflight for `POST` is answered for the Swagger UI origin¹, and an actual cross-origin `POST` from that origin carries `Access-Control-Allow-Origin`; other origins, headers and methods are rejected (ADR-0010) | web slice (rules) + end-to-end (preflight, actual request) |
| R4 | `HEAD` and `OPTIONS` on both paths are answered from the existing mappings, without extra routes (ADR-0005) | end-to-end |
| R5 | An unexpected error on either operation → `500` with `application/problem+json`; absent optional fields are omitted (ADR-0003) | web slice (shared handler) |

¹ ADR-0005 places the preflight test in the REST step; it moves to the Docker step because the Swagger UI origin is
only known once that step decides the server URL (ADR-0005, Consequences).

## Quality checks
- **Coverage:** read the JaCoCo report to find untested branches; there is no threshold.
- **Test strength:** run PIT (mutation testing) for the domain and application packages before a refactoring pull
  request, once the plugin is added (recommended). For targeted checks, temporarily break the code and show which
  tests fail; never commit the mutant, and restore the file from a copy — `git checkout -- <file>` resets it to the
  last commit and discards uncommitted work.
