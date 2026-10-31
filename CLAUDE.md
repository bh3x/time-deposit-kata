# CLAUDE.md

Guidance for Claude Code when working in this repository.

## Project
Time Deposit refactoring kata (XA Bank) — take-home assignment. The full brief is in `README.md`.
The solution is implemented in **Java** inside `java/`. The other language folders
(`c#/`, `kotlin/`, `python/`, `typescript/`) are the original starters and must not be modified.

## Goal
- Exactly **two** REST endpoints (paths, methods and payloads are **provisional** — the API contract is
  still to be designed; `openapi.yaml` becomes the source of truth once agreed):
  - list all time deposits (`id`, `planType`, `balance`, `days`, `withdrawals`) — e.g. `GET /time-deposits`
  - update the balances of all stored time deposits via `updateBalance` — e.g. `POST /time-deposits/balances`
- PostgreSQL persistence with tables `timeDeposits` and `withdrawals`
- Interest logic that is extensible to new plan types, without changing existing behaviour

## Stack
Java 25, Spring Boot 4.1, Maven Wrapper (`./mvnw`), PostgreSQL, Flyway, contract-first OpenAPI
(`openapi.yaml` + openapi-generator), JUnit 5 + AssertJ, Testcontainers, Docker Compose.

## Hard constraints
- `org.ikigaidigital.TimeDeposit`: no breaking changes (package, constructor, getters, `setBalance`).
- `TimeDepositCalculator.updateBalance(List<TimeDeposit>)`: signature unchanged and behaviour identical, including:
  - no interest when `days <= 30`
  - basic 1%, student 3% (only while `days < 366`), premium 5% (only when `days > 45`), unknown plan 0%
  - monthly interest = `balance * rate / 12`, computed as `double`
  - only the interest is rounded: `new BigDecimal(interest).setScale(2, HALF_UP)`, then added to the balance
- Characterization tests must exist and pass **before** any refactoring of the calculator.
- No additional endpoints beyond the two above.
- Where requirements are ambiguous, make a logical assumption and justify it in a code comment.

## Architecture (hexagonal)
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
- **Never change anything without explicit approval** — files, dependencies, git state.
  Read-only inspection is fine.
- Work incrementally: explain the step → wait for approval → implement → show diff → wait for approval to commit.
- Explain the *why* behind Java/Spring design choices.
- Git:
  - one short-lived branch per increment from `main` (`chore/…`, `test/…`, `refactor/…`, `feat/…`, `docs/…`)
  - Conventional Commits, atomic, build green at every commit
  - AI-assisted commits end with the trailer `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`;
    PR descriptions end with `🤖 Generated with [Claude Code](https://claude.com/claude-code)`
  - push after each approved increment; one PR per increment into `main`; merge commit (no squash)
  - only remote is `origin`; never push or open PRs against the upstream `ikigai-digital` repository
- `AI_USAGE.md` stays **brief**, as the brief requires: tools and setup, how to reproduce it, and a short summary
  of which parts were AI-assisted and why. No transcripts, prompt logs or step-by-step usage details.

## Commands
- Build & unit tests: `cd java && ./mvnw test`
- Full build incl. integration tests (Docker required): `cd java && ./mvnw verify`
- Run locally: `cd java && docker compose up`
