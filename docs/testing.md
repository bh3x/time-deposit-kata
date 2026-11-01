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
A step is done when all scenarios it touches pass at the stated level. Scenarios marked *pending* are completed — and
their level possibly adjusted — by the planned *Balance update consistency* ADR.

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
| P3 | Without an idempotency key, each call applies interest again | application |
| P4 | `days`, `publicId` and withdrawals are unchanged by a balance update | persistence |
| P5 | No deposits → `processedCount` 0 | application |
| P6 | A repeated `Idempotency-Key` returns the original outcome without applying interest again: the same `processedCount` and the identical `executedAt` (stored truncated to microseconds, ADR-0006); the response is re-rendered from the stored outcome | application (no second run) + persistence (outcome round trip) + web slice (same body) |
| P7 | Key length 1 and 255 accepted; empty and 256 characters → `400` Problem Details; a missing header is valid (P3); a whitespace-only key is valid, since the contract defines length only | web slice |
| P8 | A 255-character key is stored and replayed | persistence |
| P9 | A key still in progress → `409`; a failed request stores nothing, so a retry runs (*pending*) | application |
| P10 | Two parallel runs apply interest twice; nothing is lost (*pending*) | end-to-end |
| P11 | One deposit exceeding the column maximum aborts the whole run (*pending*) | end-to-end |
| P12 | Keys expire 24 hours after completion; a stale in-progress key is taken over (*pending*) | application |

### Persistence (ADR-0006)
| # | Scenario | Level |
|---|---|---|
| S1 | Tables `timeDeposits`, `withdrawals`, `idempotencyKeys` and their columns exist with the exact quoted names | persistence |
| S2 | `NOT NULL` and `CHECK` constraints reject invalid rows (negative balance or days, non-positive amount, invalid or incomplete idempotency status) | persistence |
| S3 | Deleting a deposit that has withdrawals is rejected (`ON DELETE RESTRICT`) | persistence |
| S4 | `publicId` is generated (random UUID) when omitted | persistence |
| S5 | Balances are written at cent precision: the double `1000.9300000000001` is stored as `1000.93` and read back as `1000.93` | persistence |
| S6 | The idempotency outcome and the balances commit or roll back together (*pending*) | end-to-end |
| S7 | The demo seed is loaded only with the `demo` profile and covers the cases listed in ADR-0006 | persistence |

### Both endpoints
| # | Scenario | Level |
|---|---|---|
| R1 | Only the two operations are mapped: an unknown path, `GET /error`, `/v3/api-docs`, `/swagger-ui.html`, `/actuator`, `/actuator/health` and `/index.html` → `404` Problem Details (ADR-0005) | end-to-end |
| R2 | A wrong method on a known path → `405` Problem Details (ADR-0005) | end-to-end |
| R3 | A CORS preflight for `POST` with `Idempotency-Key` is answered for the Swagger UI origin¹; other origins, headers and methods are rejected (ADR-0010) | web slice (rules) + end-to-end (preflight) |
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
