# CLAUDE.md

Guidance for Claude Code when working in this repository.

## Project
Time Deposit refactoring kata (XA Bank) — take-home assignment. The full brief is in `README.md`.
The solution is implemented in **Java** inside `java/`. The other language folders
(`c#/`, `kotlin/`, `python/`, `typescript/`) are the original starters and must not be modified.

## Goal
- Exactly **two** REST endpoints, defined contract-first in `java/src/main/resources/openapi/openapi.yaml`
  (source of truth; decisions in ADR-0002, ADR-0003, ADR-0004):
  - `GET /api/v1/time-deposits` — list all time deposits (`id`, `planType`, `balance`, `days`, `withdrawals`)
  - `POST /api/v1/balance-updates` — apply monthly interest (`updateBalance`) to all stored time deposits
- API changes start in the contract; never add endpoints beyond these two. Future ideas are recorded in a new ADR
  (see ADR-0004 *Future extensions*).
- `TimeDeposit` has no withdrawals: the list response is built from a separate model/read path, never by changing
  `TimeDeposit`.
- The README must explain how to trigger both endpoints using the Swagger contract.
- PostgreSQL persistence with tables `timeDeposits` and `withdrawals`
- Interest logic that is extensible to new plan types, without changing existing behaviour

## Target stack
Java 25, Spring Boot 4.1, Maven Wrapper (`./mvnw`), PostgreSQL, Flyway, contract-first OpenAPI
(`openapi.yaml` + openapi-generator), JUnit 5 + AssertJ, Testcontainers, Docker Compose.

## Hard constraints
- `org.ikigaidigital.TimeDeposit`: no breaking changes (package, constructor, getters, `setBalance`).
- `TimeDepositCalculator.updateBalance(List<TimeDeposit>)`: signature unchanged and behaviour identical, including:
  - no interest when `days <= 30`
  - basic 1%, student 3% (only while `days < 366`), premium 5% (only when `days > 45`), unknown plan 0%
  - plan types are matched as exact, case-sensitive lowercase strings: `basic`, `student`, `premium`
  - monthly interest = `balance * rate / 12`, computed as `double`
  - only the interest is rounded: `new BigDecimal(interest).setScale(2, HALF_UP)`, then added to the balance
- Characterization tests must exist and pass **before** any refactoring of the calculator.
- No additional endpoints beyond the two above, and no other HTTP routes in the application (no springdoc,
  actuator or reachable `/error`; Swagger UI runs as a separate container — ADR-0005).
- Where requirements are ambiguous, make a logical assumption and justify it in a code comment.

## Architecture (hexagonal) — provisional
The layout below is a **working assumption** until the hexagonal architecture ADR in `docs/adr/` is accepted;
this section will then be updated to match it. Until then, follow this layout as the default; any deviation
requires an ADR.
- `domain` – pure Java: entities, interest policies (Strategy + registry). No Spring/JPA imports.
- `application` – use cases and ports (`port/in`, `port/out`). No web/persistence details.
- `adapter/in/rest` – controllers implementing the generated OpenAPI interfaces.
- `adapter/out/persistence` – JPA entities, repositories, mapping (`BigDecimal` <-> `Double`).
- Dependencies point inward only: adapters → application → domain.

## Code style
- SOLID, small classes, constructor injection, no field injection.
- Prefer clear, explicit code over clever Spring "magic".
- Tests: JUnit 5 + AssertJ; integration tests use Testcontainers (`*IT.java`).

## Working rules

### Approval and interaction
1. Never change files, dependencies or git state without approval. Read-only inspection is fine.
2. Approval is per step: an approved `/step` plan covers its routine actions (writing tests, refactors inside a
   class, local edits). Show the diff and ask explicitly before each commit, and ask before pushes and PRs and
   before anything that affects the API contract, the DB schema, `TimeDeposit`, `updateBalance` behaviour, a new
   dependency, or a deviation from the brief. Git state changes still prompt as configured in
   `.claude/settings.json`.
3. Ask decisions as selectable interview questions (2–4 options, recommendation marked first). Start every
   increment by proposing 2–4 candidate `/step <branch> <goal>` invocations as options; create the branch only
   after the plan is approved.
4. Explain the *why* behind Java/Spring design choices.

### Context first
5. Before changing code, read the relevant part of `README.md`, this file, and the code being touched.
   Do not rely on what you assume the code does.
6. Every increment starts with a plan via `/step` (what changes, which files, which tests) and waits for
   approval. Non-trivial tasks inside an increment are re-planned in chat and approved before acting.
   Plan mode may be used for exploration; it does not replace `/step`.

### Understanding the requirement
7. Before implementing, list the key terms of the requirement. If a term can be read more than one way, list the
   readings. Known examples in this brief:
   - "monthly interest": one month applied per call vs. computed from `days`
   - "1 year": `days < 366` (legacy) vs. `<= 365` vs. calendar year
   - "breaking change": signature only vs. observable behaviour (treated as both)
   - "for any **existing** plans": all plans forever vs. only today's three — decide in the interest
     calculation ADR whether the 30-day rule is global or per policy
   - "endpoint": API operation vs. any HTTP route (Swagger UI, actuator) — decided in ADR-0005: both readings
     must hold, so the application serves no route beyond the two operations
8. If the readings lead to different code, ask which one is meant, or implement the reading the legacy code
   supports and record the alternatives in a code comment at the point of use, and in an ADR when significant
   (rule 33).
9. Watch scope words: "exactly", "all", "only", "no", "existing", "required", "without". They are constraints,
   not wording.
10. Start from the broadest reasonable reading (all plans, all deposits, all layers affected), then narrow it
    down. Never silently pick the easiest reading.

### Test first
11. No production code without a failing test that needs it; show the red test before making it pass.
    Exceptions: characterization tests (they pin existing behaviour and pass immediately) and pure refactoring
    (runs under already-green tests). Configuration, build and migration changes (`pom.xml`, Flyway, Compose)
    are covered by integration tests rather than a dedicated red unit test. The red state is shown, not committed.
12. Derive test cases from the rules, not from the implementation: boundaries on both sides (30/31, 45/46,
    365/366), each plan, unknown plan, zero/large balances, rounding edges, several deposits in one call.
    Prefer `@ParameterizedTest` with readable case names.
13. Characterization tests pin legacy behaviour. Once green and committed, a failing characterization test means
    the code is wrong, not the test. Never weaken, skip or delete a test to get to green — ask instead.
14. Pick the right level: domain rules → unit tests; persistence → Testcontainers ITs; contract and wiring →
    web/IT tests; architecture → ArchUnit (once adopted). Don't test the same rule twice at a higher level
    without a reason.

### Depth and completeness (design)
15. Don't stop at the first design that works. For every structural decision (interest policies, ports, read
    model, mapping, transaction boundaries), consider at least two options and say why one fits better.
16. Design APIs resource-first: resources and representations first, operations and URIs last.
17. Check every layer a change touches: contract (OpenAPI), web adapter, application/use case, domain,
    persistence (schema + mapping), tests, docs. A feature is not done if a layer is missing.
18. Try to break your own solution: inputs, sequences (e.g. calling update twice or concurrently), precision
    (`Double` ↔ `NUMERIC`, `new BigDecimal(double)`), and rule changes that would make it wrong or hard to
    extend. Add a test for each real risk.
19. Check designs against SOLID and the hexagonal rules. Name a pattern only when it is actually used
    (Strategy, Factory/Registry, Adapter). No patterns for their own sake.
20. Verify facts in primary sources: the brief, the actual code, official docs (Spring, Testcontainers, Flyway,
    OpenAPI Generator), Maven Central / Docker Hub for versions. Never pin a version or API from memory.
21. After every change to `openapi.yaml`, lint it with the pinned command under `## Commands` and report the
    result.

### Honesty over agreement
22. The goal is a correct, reviewable solution, not agreement. If an instruction conflicts with the brief, the
    hard constraints, SOLID or test-first, say so with evidence before acting.
23. If a better approach exists than the one requested, present it with its trade-offs, even unasked.
    The human decides.
24. Don't reverse a correct technical position because of pushback alone; change it when given new facts,
    a failing test, or a requirement you missed.

### Honesty about limits
25. When reporting, state exactly what was run and what wasn't: which tests, unit vs. IT, whether
    Docker/Testcontainers actually ran, whether the app was started and the endpoints called.
26. Never say "done", "works" or "passes" without having run it in this session. If a check couldn't run,
    say so and why.
27. Match confidence to evidence. Mark anything based on memory or one unverified source as unverified.

### Git and pull requests
28. One short-lived branch per increment from `main` (`chore/…`, `test/…`, `refactor/…`, `feat/…`, `docs/…`).
29. Atomic Conventional Commits; build green at every commit. AI-assisted commits end with
    `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`; PR descriptions end with
    `🤖 Generated with [Claude Code](https://claude.com/claude-code)`.
30. Run `/review` before `/ship`. After each approved increment, push and open the PR via `/ship`; one PR per
    increment into `main`; merge commit, no squash.
31. Only remote is `origin`; never push or open PRs against the upstream `ikigai-digital` repository.
32. Out-of-scope findings (from reviews or while working) are not fixed on the spot: list them under
    **Follow-ups** in the PR description with the step they belong to; design items are also added to
    "Planned decisions" in `docs/adr/README.md`.

### Documentation
33. Record significant decisions and assumptions as ADRs following `docs/adr/README.md`, **before implementation**
    of the affected code; the ADR status flip to `Accepted` is the last commit of its PR. Every assumption is
    also justified in a code comment where it applies; minor assumptions need only the code comment.
34. `AI_USAGE.md` stays brief: tools and setup, how to reproduce it, and a short summary of which parts were
    AI-assisted and why. No transcripts, prompt logs or per-step usage details.

### Definition of done
35. **Code increment:** failing test first (rule 11) → green `./mvnw verify` → no compiler warnings (enforced
    with `-Xlint:all -Werror`, configured in the tooling step; generated sources excluded or targeted
    `-Xlint:-<key>` suppressions justified there) → ArchUnit green (once adopted via ADR) →
    assumptions recorded (rule 33) → atomic Conventional Commits → `/review` (incl. brief compliance) clean or
    findings listed as Follow-ups (rule 32) → `/ship`.
36. **Docs/design increment:** contract lint clean or warnings accepted in an ADR (rule 21) → ADRs and indexes
    consistent → `/review` clean or findings listed as Follow-ups (rule 32) → ADR status flip last → `/ship`.

## Commands
- Build & unit tests: `cd java && ./mvnw test`
- Full build incl. integration tests (Docker required): `cd java && ./mvnw verify`
- Run locally: `cd java && docker compose up`
- Lint the API contract (Docker required):
  `docker run --rm --network none -v "$(git rev-parse --show-toplevel)/java/src/main/resources/openapi:/spec:ro" redocly/cli:2.68.0 lint /spec/openapi.yaml`
