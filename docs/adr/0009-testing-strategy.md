# ADR-0009: Testing strategy

- **Status:** Proposed
- **Date:** 2026-10-31

## Context
The coming steps implement the two endpoints through every layer of the hexagon (ADR-0008): application services,
persistence on PostgreSQL (ADR-0006), the REST adapter generated from the contract (ADR-0002 to ADR-0005). Without
a shared strategy each step would choose its own test levels, doubles and tools, rules would be tested several
times or not at all, and "done" would not be measurable. ADR-0007 already fixes the suites (`test` without Docker,
`integrationTest` with Testcontainers); CLAUDE.md requires tests first (rule 11), rule-derived cases (rule 12),
never weakening characterization tests (rule 13) and the right level per rule (rule 14). The practical guide with
the acceptance scenarios per endpoint is [docs/testing.md](../testing.md).

**Terms as used here**
- **Unit test:** tests one unit of behaviour through its public API without external dependencies. *Sociable*:
  collaborators inside the hexagon are real; only `port.out` interfaces are replaced.
- **Integration test:** tests code against a real external dependency (PostgreSQL via Testcontainers).
- **End-to-end test:** the whole application in-process — full Spring context, real HTTP port, real database.
- **Contract conformance:** requests and responses are checked against `openapi.yaml` (not consumer-driven
  contracts: the API has no known consumer).

## Options considered
1. **Unit tests for services:** sociable (fakes only at `port.out`) · solitary (every collaborator doubled).
2. **Suite split:** Docker needed or not · Spring context or not.
3. **End-to-end:** in-process full stack · additionally the Docker Compose stack from outside.
4. **Test doubles:** fakes by default, mocks for use cases in web slices · no mocking library · mocks anywhere.
5. **Fakes location:** `src/test` next to the port's package · a `testFixtures` source set.
6. **Web tests:** many slice tests plus few end-to-end tests · end-to-end only. Clients: `MockMvcTester` or
   classic `MockMvc` for slices; `RestTestClient`, `TestRestTemplate` or `RestClient` for a live server.
7. **Contract conformance:** an OpenAPI validator in tests · explicit assertions only · JSON snapshot files.
8. **Persistence tests:** `@DataJpaTest` slices with a shared `@ServiceConnection` container · full
   `@SpringBootTest` for every DB test · a container per test class. Data: explicit per test · shared SQL files.
   Isolation: rollback in slices and truncate in end-to-end · truncate everywhere.
9. **Time and concurrency:** injected `java.time.Clock` · an own time port; plain `java.util.concurrent` ·
   Awaitility.
10. **Quality gates:** coverage report · coverage threshold in `check`; PIT on demand · manual mutants only · PIT as
    a gate.
11. **Characterization suite:** permanent regression suite · retired after the interest-policy refactoring.

## Decision

### Levels and suites
| Element | Level | Collaborators | Suite |
|---|---|---|---|
| Domain and kernel (`TimeDeposit`, `TimeDepositCalculator`, later interest policies) | unit | real objects | `test` |
| Application services | unit, sociable | real domain; in-memory fakes for `port.out` | `test` |
| REST adapter (mapping, status codes, Problem Details, header validation) | web slice (`@WebMvcTest`) | `port.in` use cases as `@MockitoBean` | `test` |
| Persistence adapters (queries, mapping, constraints) | integration (`@DataJpaTest`) | real PostgreSQL | `integrationTest` |
| Whole application (HTTP → database), route scope of ADR-0005 | end-to-end (`@SpringBootTest`, random port) | full context, real PostgreSQL | `integrationTest` |
| Architecture rules (ADR-0008) | ArchUnit | – | `test` |
| Docker Compose stack | manual smoke check, documented in the README | – | – |

- **Split criterion:** a test belongs in `integrationTest` exactly when it needs Docker; Spring web slices stay in
  `test`.
- **Each rule is tested once, at the lowest level that can show it** (CLAUDE.md rule 14). Higher levels only add
  what lower levels cannot show: wiring, persistence round trips, full auto-configuration.

### Test doubles
- **Fakes by default:** hand-written in-memory implementations of `port.out` interfaces, which also record calls
  where a scenario needs it (e.g. "nothing saved on replay"). They live in `src/test`, next to the port's package
  (e.g. `…/timedeposit/application/fake/`).
- **Mocks only in web slices:** the `port.in` use cases are `@MockitoBean`s (the Spring idiom; availability
  verified when introduced). Domain and legacy classes are never mocked.

### Web tests
- **Slices** (`MockMvcTester`) cover HTTP details: request mapping, status codes, JSON shape, money format,
  Problem Details, `Idempotency-Key` validation.
- **End-to-end** (`RestTestClient` against the live server; fall back to `RestClient` if verification fails) cover
  one happy path per endpoint through all layers and the route scope of ADR-0005 (only the two operations, `404`/
  `405` Problem Details), which needs the complete auto-configuration.
- **Contract conformance:** an OpenAPI validator checks slice and end-to-end responses against `openapi.yaml`, in
  addition to explicit assertions. The library is chosen in the REST step after verifying support for Spring
  Framework 7, Jakarta and OpenAPI 3.0.3; if none qualifies, explicit assertions remain.

### Persistence tests
- **Adapters** are tested as `@DataJpaTest` slices on real PostgreSQL (no embedded database) with the adapter
  imported and Flyway migrations applied. One container is defined once in a test configuration with
  `@ServiceConnection` and shared through Spring's context cache; its image tag is pinned and identical to Docker
  Compose. Details are verified against the Spring Boot documentation in the persistence step.
- **Test data** is inserted explicitly by each test through a small helper (SQL with the quoted camelCase names of
  ADR-0006, or test-data builders). The demo seed is never loaded, except in one test of the demo profile itself.
- **Isolation:** `@DataJpaTest` rolls back each test; end-to-end and concurrency tests commit for real and truncate
  the tables before each test.

### Determinism
- No `Thread.sleep`, no dependence on test order, fixed seeds for generated input, times asserted in UTC.
- **Time:** services receive a `java.time.Clock` through the constructor; production wires `Clock.systemUTC()` as a
  bean, tests use a fixed or manually advanced clock.
- **Concurrency:** threads start together via `CountDownLatch`/`CyclicBarrier`, waits are bounded by timeouts, and
  outcomes are asserted on the final database state; in-flight situations are held deterministically by a blocking
  test double, not by timing.

### Quality gates
- `./gradlew check` (unit, architecture and integration tests, `-Xlint:all -Werror`) is the green criterion for
  every pull request (CLAUDE.md rule 35).
- **Coverage:** one JaCoCo report over both suites, without a threshold. The acceptance scenarios and mutation
  checks are the quality bar; a percentage invites low-value tests.
- **Test strength:** PIT mutation testing for the domain and application packages as a separate task, outside
  `check`, run before refactoring pull requests. The plugin is verified for Java 25 and approved when added;
  targeted manual mutants remain for specific checks.

### Naming, layout and data
- AssertJ only; tests in the package of the class under test; `@Nested` with `@DisplayName` per rule;
  `@ParameterizedTest` with readable names for case tables; arrange / act / assert separated by blank lines.
- Classes are named `<Subject>Test` (e.g. `ListTimeDepositsServiceTest`), end-to-end tests
  `<UseCase>EndToEndTest`; methods are behaviour sentences in camelCase (e.g. `eachCallAppliesInterestAgain`).
- Test data comes from a small factory or builder per capability (e.g. `TimeDepositFixtures`) that spells out only
  the fields relevant to a test.

### Characterization suite
The calculator characterization tests, the frozen legacy copy and the randomized comparison are a **permanent
regression suite** for the public `updateBalance` API the brief protects. They are only extended, never weakened
or removed (CLAUDE.md rule 13); the frozen copy is never edited. Unit tests of future interest policies document
the new design and do not replace this suite.

### Acceptance scenarios
Every endpoint step is done when its scenarios in [docs/testing.md](../testing.md) are covered at the stated level.
The planned *Balance update consistency* ADR completes the scenarios for in-flight requests, concurrent runs and
overflow.

## Consequences
- Each later step plans its tests against this ADR and the scenario list; reviews check the level and the double
  used for every new test.
- New test dependencies are added by the steps that need them and verified then (CLAUDE.md rule 20): Spring Boot's
  test starter (Mockito, `MockMvcTester`, `RestTestClient`), Testcontainers PostgreSQL, an OpenAPI validator, the
  PIT plugin.
- The JaCoCo report is extended to include the `integrationTest` suite once it has tests.
- Fakes and fixtures are production-quality test code: they implement the real ports and are reviewed like
  production code.
- Testing Docker Compose stays manual; a regression there is found by the README's smoke check, not by `check`.
