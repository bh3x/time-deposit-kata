# ADR-0015: Testing strategy as built

- **Status:** Proposed
- **Date:** 2026-11-01

## Context
ADR-0009 set the testing strategy before the code existed. A full review of `main` found four places where the
suite, as built, differs from it:
- **Contract conformance:** ADR-0009 decided an OpenAPI validator for slice and end-to-end responses, "if none
  qualifies, explicit assertions remain". No validator was added.
- **Test strength:** ADR-0009 decided a PIT mutation-testing task outside `check`. No PIT plugin was added; targeted
  manual mutants (code broken on purpose, never committed, recorded in the pull requests) were used instead.
- **Doubles:** ADR-0009 says integration and end-to-end tests use no doubles. A test now needs to see the SQL text
  that reaches the driver, which the results cannot show; it uses a recording `DataSource` that passes every call
  through to PostgreSQL.
- **Containers:** ADR-0009 says the suite starts PostgreSQL once. The container is a bean of a test configuration,
  so each distinct cached Spring test context starts its own container.

Accepted ADRs are not edited, so the outcome is recorded here.

## Options considered
1. **Contract conformance:** add a validator now (a new test dependency; support for Spring Framework 7, Jakarta and
   OpenAPI 3.0.3 to be verified) · **rely on what already enforces the contract** — the controller implements the
   generated interface (an operation, path or status signature that drifts fails compilation), the response DTOs are
   generated from the schemas, and the web slice compares bodies strictly (`isStrictlyEqualTo`,
   `JSONCompareMode.NON_EXTENSIBLE`), so a missing or extra field fails a test.
2. **Test strength:** add PIT now (a new build plugin; Java 25 support unverified) · **defer PIT** and keep targeted
   manual mutants for the code a pull request changes.
3. **Pass-through recorders:** forbid them and give up checking statement text · **allow recorders that replace
   and stub nothing** — every call still reaches the real database, so the test stays an integration test.
4. **Containers:** one static container for the whole suite (fewer containers, faster suite; a test-infrastructure
   change) · **record the current behaviour** — one container per cached test context, which also keeps the demo
   seed in its own database.

## Decision
| Topic | Decision | Main reason |
|---|---|---|
| Contract conformance | **No runtime validator** (ADR-0009's fallback) | The generated interface and DTOs plus strict JSON slice assertions already fail on drift; a validator would add a dependency for little extra |
| Test strength | **PIT deferred**; targeted manual mutants meanwhile | A new plugin needs verification and approval; manual mutants, recorded in the pull requests, show that the changed tests catch mistakes |
| Pass-through recorders | **Allowed** in integration tests, if they replace and stub nothing | Statement text is otherwise unobservable; the real database still answers every call |
| Containers | **One container per cached test context** | Matches the code; a single shared container is a planned improvement |

**Supersedes in part ADR-0009:** *Web tests — Contract conformance*, *Quality gates — Test strength* (and the PIT
mention under *Coverage*), the "no doubles" sentence under *Test doubles* for pass-through recorders, and "the suite
starts PostgreSQL once" under *Persistence tests*. All other decisions of ADR-0009 stand.

## Consequences
- `docs/testing.md` states G6's level as the web slice with strict JSON plus the generated interface, and describes
  manual mutants instead of a PIT task.
- PIT and a single shared test container are listed under *Planned decisions* in `docs/adr/README.md`.
- `RecordingDataSource` (integration suite) is the first pass-through recorder; it documents in its Javadoc that it
  stubs nothing.
- Reviews keep checking the double of every new test (ADR-0009): a recorder that starts to stub or replace answers
  is a double and is not allowed in integration tests.
- The status line of ADR-0009 records the partial supersession.
