# Testing guide

How to test changes in this repository. The decisions and their reasons are in
[ADR-0009](adr/0009-testing-strategy.md); the architecture they refer to is in
[ADR-0008](adr/0008-hexagonal-architecture.md).

## Running the tests
| Command (in `java/`) | Runs | Needs Docker |
|---|---|---|
| `./gradlew test` | unit, web slice and architecture tests (`src/test`) | no |
| `./gradlew check` | everything, incl. integration and end-to-end tests (`src/integrationTest`) | yes |
| `./gradlew jacocoTestReport` | coverage report (`build/reports/jacoco/`) | as for the suites included |

`./gradlew check` must be green for every pull request.

## Where does my test go?
| I am testing… | Write a… | Doubles | Location |
|---|---|---|---|
| a domain rule (calculator, interest policy) | unit test | none | `src/test`, package of the class |
| a use case (`…Service`) | sociable unit test through its `port.in` | in-memory fakes of `port.out` | `src/test`, package of the service |
| HTTP mapping, status code, JSON, Problem Details | web slice test (`@WebMvcTest`, `MockMvcTester`) | use cases as `@MockitoBean` | `src/test`, `adapter/in/rest` package |
| a query, mapping or constraint against the database | `@DataJpaTest` on PostgreSQL | none | `src/integrationTest`, `adapter/out/persistence` package |
| the whole flow HTTP → database, or which routes exist | end-to-end test (`@SpringBootTest`, `RestTestClient`) | none | `src/integrationTest` |
| a package dependency rule | ArchUnit rule | – | `src/test/.../architecture` |

Test each rule **once**, at the **lowest** level that can show it. Do not repeat interest rules at endpoint level:
the characterization tests pin them at the calculator.

## Writing tests
- AssertJ only. Arrange / act / assert, separated by blank lines.
- Class names: `<Subject>Test`; end-to-end: `<UseCase>EndToEndTest`. Method names are behaviour sentences in
  camelCase: `returnsEmptyListWhenNoDeposits()`.
- Group by rule with `@Nested` + `@DisplayName`; use `@ParameterizedTest` with a readable `name` for case tables.
- Derive expected values from the rules, not from the code; cover both sides of every boundary (CLAUDE.md rule 12).
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
An endpoint step is done when all its scenarios pass at the stated level. Scenarios marked *pending* are completed
by the planned *Balance update consistency* ADR.

### `GET /api/v1/time-deposits`
| # | Scenario | Level |
|---|---|---|
| G1 | Returns every deposit with `id` (public UUID), `planType`, `balance`, `days`, `withdrawals` | end-to-end |
| G2 | Empty database → `200` with `[]` | web slice |
| G3 | A deposit without withdrawals has `"withdrawals": []`, never a missing field | persistence + web slice |
| G4 | Deposits ordered by internal key, withdrawals by `date` then internal id | persistence |
| G5 | Balances as two-decimal strings: `1000.9300000000001` → `"1000.93"`, `0` → `"0.00"`, large values | unit + web slice |
| G6 | No internal key and no `timeDepositId` in the response | web slice + contract validation |
| G7 | Unknown plan types are listed unchanged (e.g. `"gold"`) | web slice |
| G8 | An unexpected error → `500` Problem Details | web slice |

### `POST /api/v1/balance-updates`
| # | Scenario | Level |
|---|---|---|
| P1 | One month of interest is applied to all deposits and stored | end-to-end |
| P2 | `200` with `processedCount` (all deposits, incl. unchanged ones) and `executedAt` in UTC | application + web slice |
| P3 | Without an idempotency key, each call applies interest again | application |
| P4 | `days`, `publicId` and withdrawals are unchanged by a balance update | persistence |
| P5 | Empty database → `processedCount` 0 | application |
| P6 | A repeated `Idempotency-Key` returns the original status and body without applying interest again | application + end-to-end |
| P7 | A malformed key (empty, longer than 255 characters) → `400` Problem Details | web slice |
| P8 | A key still in progress → `409`; a failed request stores nothing, so a retry runs (*pending*) | application |
| P9 | Two parallel runs apply interest twice; nothing is lost (*pending*) | integration |
| P10 | One deposit exceeding the column maximum aborts the whole run (*pending*) | integration |

### Both endpoints (ADR-0005)
| # | Scenario | Level |
|---|---|---|
| R1 | Only the two operations are mapped; an unknown path, `GET /error`, `/v3/api-docs` and `/actuator` → `404` Problem Details | end-to-end |
| R2 | A wrong method on a known path → `405` Problem Details | end-to-end |
| R3 | A CORS preflight for `POST` with `Idempotency-Key` is answered for the Swagger UI origin | end-to-end (Docker step) |

## Quality checks
- **Coverage:** JaCoCo report over both suites, no threshold — read it to find untested branches.
- **Test strength:** run PIT (mutation testing) for the domain and application packages before a refactoring pull
  request, once the plugin is added; for targeted checks, temporarily break the code and show which tests fail
  (never commit the mutant, and restore from a copy, not with `git checkout`).
