# ADR-0002: Contract-first OpenAPI approach

- **Status:** Proposed
- **Date:** 2026-10-31

## Context
The brief asks for exactly two REST endpoints and prefers an OpenAPI (Swagger) contract. The API must be
documented, triggerable via Swagger, and stable while the implementation is refactored behind it.

## Options considered
1. **Code-first** — annotate controllers, generate the spec from code (e.g. springdoc). Fast; the contract is a
   by-product and drifts with implementation details.
2. **Contract-first** — hand-written OpenAPI document is the source of truth; server interfaces and DTOs are
   generated from it. Contract is reviewed before code; implementation must conform.

## Decision
**Contract-first**, with these foundations:
- **Location:** `java/src/main/resources/openapi/openapi.yaml` — next to the code that generates from it and
  serves it, so no copy step is needed.
- **Specification version:** OpenAPI **3.0.3** — the most mature support in openapi-generator and Swagger UI.
- **Base path:** `/api/v1`, declared once in `servers`; resource paths are relative to it.
- **Usage:** server interfaces and DTOs are generated from the contract at build time (implementation phase);
  Swagger UI serves the same file.
- Endpoint design is recorded separately (time deposit API design ADR).

## Consequences
- API changes start in `openapi.yaml` and are reviewed before code changes.
- Controllers implement generated interfaces; a mismatch with the contract fails the build.
- Requires the generator plugin in the Maven build (tooling step). Generated models use the suffix `Dto`
  (e.g. `TimeDepositDto`) so they never clash with domain classes such as the legacy `TimeDeposit`; schema names
  in the contract stay suffix-free.
- The contract is validated with Redocly CLI (recommended ruleset). The `info-license` warning is accepted:
  no license is declared for this exercise.
