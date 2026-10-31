# ADR-0001: Record architecture decisions

- **Status:** Accepted
- **Date:** 2026-10-31

## Context
The solution is built in small, reviewed increments. Reviewers need to understand not only *what* was built but
*why*, and the documentation must stay lightweight given the time box.

## Options considered
1. **No dedicated docs** (README only) — fastest; reasoning gets lost.
2. **arc42 full template** — comprehensive; too heavy for the scope.
3. **Lightweight: ADRs + one C4 overview** — captures decisions and the big picture with little overhead.

## Decision
Option 3:
- **ADRs** in `docs/adr/`, MADR-lite format (Context, Options considered, Decision, Consequences),
  named `NNNN-kebab-case-title.md`, indexed in `docs/adr/README.md`.
- **Architecture overview** in `docs/architecture/overview.md`, using the **C4 model** levels 1–3
  (context, containers, components).
- **Diagrams in Mermaid**, embedded in Markdown — rendered by GitHub, text-based, reviewable in diffs.

## Consequences
- ADRs are written before or together with the change they describe; design decisions (API, data model,
  architecture) are accepted before implementation starts.
- The overview is updated when an accepted ADR changes the structure.
- No extra tooling is required to read or render the documentation.
