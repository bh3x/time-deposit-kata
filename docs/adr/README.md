# Architecture Decision Records

An ADR captures one significant decision: its context, the options considered, the decision and its consequences.

- **When:** a decision affects structure, interfaces, data, dependencies or is hard to reverse.
- **How:** copy [`template.md`](template.md) to `NNNN-kebab-case-title.md` (next free number), fill it in,
  add it to the index below.
- **Status:** `Proposed` → `Accepted` or `Rejected`; an accepted ADR may later become `Deprecated` or
  `Superseded by ADR-NNNN`. An ADR becomes `Accepted` when its pull request is merged.
  Accepted ADRs are not edited, except for the Status line when they are deprecated or superseded;
  a change of mind is a new ADR that supersedes the old one.

## Index
| ADR | Title | Status | Date |
|---|---|---|---|
| [0001](0001-record-architecture-decisions.md) | Record architecture decisions | Accepted | 2026-10-31 |
| [0002](0002-contract-first-openapi.md) | Contract-first OpenAPI approach | Proposed | 2026-10-31 |
| [0003](0003-api-conventions.md) | API conventions | Proposed | 2026-10-31 |
| [0004](0004-time-deposit-api-design.md) | Time deposit API design | Proposed | 2026-10-31 |

## Planned decisions
Numbers are assigned when each ADR is written.
- Data model
- Hexagonal architecture
- Interest calculation design
