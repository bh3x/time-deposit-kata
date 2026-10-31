# ADR-0009: Testing strategy

- **Status:** Accepted
- **Date:** 2026-10-31

## Context
The coming steps implement the two endpoints through every layer of the hexagon (ADR-0008): application services,
persistence on PostgreSQL (ADR-0006), the REST adapter generated from the contract (ADR-0002 to ADR-0005). Without
a shared strategy each step would choose its own test levels, doubles and tools, rules would be tested several
times or not at all, and "done" would not be measurable. ADR-0007 already fixes the suites (`test` without Docker,
`integrationTest` with Testcontainers); CLAUDE.md requires tests first (rule 11), rule-derived cases (rule 12),
never weakening characterization tests (rule 13) and the right level per rule (rule 14). The practical guide with
the acceptance scenarios is [docs/testing.md](../testing.md).

**Terms as used here**
- **Unit test:** tests one unit of behaviour through its public API without external dependencies. *Sociable*:
  collaborators inside the hexagon are real; only `port.out` interfaces are replaced.
- **Integration test:** tests an adapter against a real external dependency (PostgreSQL via Testcontainers).
- **End-to-end test:** the whole application in-process — full Spring context, real database, and a real HTTP port
  where HTTP matters.
- **Contract conformance:** requests and responses are checked against `openapi.yaml` (not consumer-driven
  contracts: the API has no known consumer).

## Options considered
1. **Unit tests for services:** sociable — tests behaviour, survives refactoring inside the hexagon · solitary —
   isolates each class, but couples tests to how classes collaborate.
2. **Suite split:** Docker needed or not — matches ADR-0007, `test` stays fast and Docker-free · Spring context or
   not — moves Docker-free web slices into the slow suite.
3. **End-to-end:** in-process full stack — fast enough, debuggable, deterministic · additionally the Docker Compose
   stack — tests the deployment too, but slow and with more moving parts.
4. **Test doubles:** fakes by default, mocks only for use cases in web slices — behaviour-focused, Spring idiom
   where it is expected · no mocking library — more hand-written doubles in web slices · mocks anywhere — least
   code, most coupling to implementation details.
5. **Fakes location:** `src/test` next to the ports — no extra source set · a `testFixtures` source set — shareable
   with other suites and modules, not needed while fakes serve unit tests only.
6. **Web tests:** many slice tests plus few end-to-end tests — HTTP details tested fast, wiring tested once · only
   end-to-end — every web test needs Docker. Clients: `MockMvcTester` (AssertJ style) or classic `MockMvc`;
   `RestTestClient`, `TestRestTemplate` (maintenance mode) or plain `RestClient` for a live server.
7. **Contract conformance:** an OpenAPI validator in tests — catches drift from the contract automatically · explicit
   assertions only — no dependency, drift can slip through · JSON snapshots — readable diffs, but snapshots drift
   and do not enforce the schema.
8. **Persistence tests:** `@DataJpaTest` slices with a shared container — focused and fast · full `@SpringBootTest`
   for every DB test — mixes web wiring into DB tests · a container per class — strongest isolation, slowest. Data:
   explicit per test — readable, no hidden coupling · shared SQL files — fixtures drift from tests. Isolation:
   rollback in slices and truncate where commits are needed · truncate everywhere — uniform, slower.
9. **Time and concurrency:** injected `java.time.Clock` — the JDK's abstraction, testable · an own time port — a
   custom abstraction for the same thing; plain `java.util.concurrent` — no dependency, explicit · Awaitility —
   polling helpers, only needed for asynchronous behaviour.
10. **Quality gates:** coverage report · coverage threshold in `check` — hard signal, but invites low-value tests;
    PIT on demand · manual mutants only · PIT as a gate — strongest, slowest build.
11. **Characterization suite:** permanent regression suite · retired after the interest-policy refactoring — avoids
    two suites for the same rules, but drops the guard on the API the brief protects.

## Decision

### Levels and suites
| Element | Level | Collaborators | Suite |
|---|---|---|---|
| Domain and kernel (`TimeDeposit`, `TimeDepositCalculator`, later interest policies) | unit | real objects | `test` |
| Application services | unit, sociable | real domain; in-memory fakes for `port.out` | `test` |
| REST adapter (mapping, status codes, Problem Details, header validation) | web slice (`@WebMvcTest`) | `port.in` use cases as `@MockitoBean` | `test` |
| Persistence adapters (schema, queries, mapping, constraints) | integration (`@DataJpaTest`) | real PostgreSQL | `integrationTest` |
| Whole application: HTTP → database, route scope of ADR-0005, transactions spanning service and database | end-to-end (`@SpringBootTest`) | full context, real PostgreSQL | `integrationTest` |
| Architecture rules (ADR-0008) | ArchUnit | – | `test` |
| Docker Compose stack | manual smoke check, documented in the README | – | – |

- **Unit tests are sociable**, because they test behaviour through `port.in` and keep passing when the inside of the
  hexagon is refactored (e.g. the planned interest policies); solitary tests would break on every such change.
- **A test belongs in `integrationTest` exactly when it needs Docker**, so `./gradlew test` stays fast and runs
  anywhere (ADR-0007); Spring web slices need no Docker and stay in `test`.
- **End-to-end runs in-process**, because it is fast, debuggable and deterministic; the Compose stack adds only
  deployment wiring, which the README's smoke check covers. The end-to-end tests also fulfil ADR-0008's "one context
  test checks the wiring".
- **Each rule is tested once, at the lowest level that can show it** (CLAUDE.md rule 14). Higher levels only add
  what lower levels cannot show: wiring, persistence round trips, transactions, full auto-configuration.
- **List order** is asserted only where the order itself is the requirement (scenario G4); everywhere else lists
  are compared in any order (ADR-0004: tests must not rely on collection order).

### Test doubles
- **Fakes by default:** hand-written in-memory implementations of `port.out` interfaces, which also record calls
  where a scenario needs it (e.g. "nothing saved on replay"). Fakes test behaviour and survive refactoring; mocks
  verify calls and break on harmless changes. They live in `src/test` under `…/application/port/out/fake/` of their
  capability; a `testFixtures` source set is not needed while only unit tests use them.
- **Mocks only in web slices:** the `port.in` use cases are `@MockitoBean`s — the Spring idiom for slices.
  Domain and legacy classes are never mocked.
- **Integration and end-to-end tests use no doubles.** Situations that need a precise interleaving (e.g. a request
  still in progress) are tested at application level with a blocking `port.out` fake; in integration tests they are
  produced with real database locks or transactions opened by the test.

### Web tests
- **Slices** (`MockMvcTester`, the AssertJ style used everywhere else) cover HTTP details: request mapping, status
  codes, JSON shape, money format, Problem Details, `Idempotency-Key` validation.
- **End-to-end** tests against the live server (`RestTestClient`; fall back to `RestClient` if verification fails)
  cover one happy path per endpoint through all layers and the route scope of ADR-0005, which needs the complete
  auto-configuration.
- **Contract conformance:** an OpenAPI validator checks slice and end-to-end responses against `openapi.yaml`, in
  addition to explicit assertions, so drift from the contract fails a test instead of reaching a client. The
  library is chosen in the REST step after verifying support for Spring Framework 7, Jakarta and OpenAPI 3.0.3; if
  none qualifies, explicit assertions remain.
- **Verification:** the dependencies, packages and annotations named here (`@WebMvcTest`, `@MockitoBean`,
  `MockMvcTester`, `RestTestClient`; Spring Boot 4 may split test support into several modules) are verified in the
  step that introduces them (CLAUDE.md rule 20).

### Persistence tests
- **Adapters** are tested as `@DataJpaTest` slices on real PostgreSQL (no embedded database, so constraints and
  quoted names behave as in production), with the adapter imported and Flyway migrations applied. One container is
  defined once in a test configuration with `@ServiceConnection` and shared through Spring's context cache, so the
  suite starts PostgreSQL once; its image tag is pinned and identical to Docker Compose. Details are verified against
  the Spring Boot documentation in the persistence step.
- **Test data** is inserted explicitly by each test through a small helper (SQL with the quoted camelCase names of
  ADR-0006, or test-data builders), so every test shows what it depends on. The demo seed is never loaded, except
  in the test of the demo profile itself (scenario S7).
- **Isolation:** `@DataJpaTest` rolls back each test, which is fast; end-to-end and concurrency tests must commit
  for real and truncate the tables before each test.

### Determinism
- No `Thread.sleep`, no dependence on test order, fixed seeds for generated input, times asserted in UTC.
- **Time:** services receive a `java.time.Clock` through the constructor (the JDK's abstraction, allowed in the
  application layer). Production wires `Clock.systemUTC()` as an explicit `@Bean` in `configuration` (ADR-0008);
  tests use a fixed or manually advanced clock.
- **Concurrency:** threads start together via `CountDownLatch`/`CyclicBarrier`, waits are bounded by timeouts, and
  outcomes are asserted on the final database state. No extra library is needed while the endpoints are
  synchronous.

### Quality gates
- `./gradlew check` (unit, architecture and integration tests, `-Xlint:all -Werror`) is the green criterion for
  every pull request (CLAUDE.md rule 35).
- **Coverage:** one JaCoCo report, extended to both suites once `integrationTest` has tests, without a threshold.
  The acceptance scenarios and mutation checks are the quality bar; a percentage invites low-value tests.
- **Test strength:** PIT mutation testing for the domain and application packages as a separate task, outside
  `check` so the build stays fast. Running it before refactoring pull requests is recommended, not enforced; the plugin
  is verified for Java 25 and approved when added. Targeted manual mutants remain for specific checks.

### Naming, layout and data
- AssertJ only; tests in the package of the class under test; `@Nested` with `@DisplayName` per rule;
  `@ParameterizedTest` with readable names for case tables; arrange / act / assert separated by blank lines.
- Classes are named `<Subject>Test` (e.g. `ListTimeDepositsServiceTest`), end-to-end tests
  `<UseCase>EndToEndTest`; the source set already shows whether Docker is needed. Methods are behaviour sentences in
  camelCase (e.g. `eachCallAppliesInterestAgain`), consistent with the existing tests.
- Test data comes from a small factory or builder per capability (e.g. `TimeDepositFixtures`) that spells out only
  the fields relevant to a test, so tests stay readable as entities grow.

### Characterization suite
The calculator characterization tests, the frozen legacy copy and the randomized comparison are a **permanent
regression suite** for the public `updateBalance` API the brief protects, whatever happens to its internals. They
are only extended, never weakened or removed (CLAUDE.md rule 13); the frozen copy is never edited. Unit tests of
future interest policies document the new design and do not replace this suite.

### Acceptance scenarios
Every endpoint step is done when its scenarios in [docs/testing.md](../testing.md) are covered at the stated level.
The planned *Balance update consistency* ADR completes the scenarios marked *pending* (in-flight requests,
concurrent runs, overflow, retention, stale keys, transaction atomicity) and may adjust their level.

## Consequences
- Each later step plans its tests against this ADR and the scenario list (the `test-designer` subagent derives them
  in `/step`); reviews check the level and the double of every new test.
- New test dependencies are added by the steps that need them and verified then (CLAUDE.md rule 20): Spring Boot's
  test support (Mockito, `MockMvcTester`, `RestTestClient`), Testcontainers PostgreSQL, an OpenAPI validator, the
  PIT plugin.
- The JaCoCo report is extended to include the `integrationTest` suite once it has tests.
- Fakes and fixtures are production-quality test code: they implement the real ports and are reviewed like
  production code.
- Testing Docker Compose stays manual; a regression there is found by the README's smoke check, not by `check`.
