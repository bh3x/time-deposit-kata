# Architecture Decision Records

An ADR captures one significant decision: its context, the options considered, the decision and its consequences.

- **When:** a decision affects structure, interfaces, data, dependencies or is hard to reverse.
- **How:** copy [`template.md`](template.md) to `NNNN-kebab-case-title.md` (next free number), fill it in,
  add it to the index below.
- **Status:** `Proposed` → `Accepted` or `Rejected`; an accepted ADR may later become `Deprecated` or
  `Superseded by ADR-NNNN`. An ADR becomes `Accepted` when its pull request is merged: the last commit of
  that pull request sets the status to `Accepted` (file and index), so merging the PR is the acceptance.
  Accepted ADRs are not edited, except for the Status line when they are deprecated or superseded;
  a change of mind is a new ADR that supersedes the old one.

## Index
| ADR | Title | Status | Date |
|---|---|---|---|
| [0001](0001-record-architecture-decisions.md) | Record architecture decisions | Accepted | 2026-10-31 |
| [0002](0002-contract-first-openapi.md) | Contract-first OpenAPI approach | Accepted | 2026-10-31 |
| [0003](0003-api-conventions.md) | API conventions | Accepted | 2026-10-31 |
| [0004](0004-time-deposit-api-design.md) | Time deposit API design | Accepted | 2026-10-31 |
| [0005](0005-endpoint-scope.md) | Endpoint scope | Accepted | 2026-10-31 |
| [0006](0006-data-model.md) | Data model | Accepted | 2026-10-31 |
| [0007](0007-technology-stack-and-build.md) | Technology stack and build | Accepted | 2026-10-31 |
| [0008](0008-hexagonal-architecture.md) | Hexagonal architecture | Accepted | 2026-10-31 |
| [0009](0009-testing-strategy.md) | Testing strategy | Accepted | 2026-10-31 |
| [0010](0010-local-runtime.md) | Local runtime with Docker Compose | Accepted | 2026-11-01 |
| [0011](0011-interest-calculation-design.md) | Interest calculation design | Accepted | 2026-11-01 |

## Planned decisions
Numbers are assigned when each ADR is written.
- Balance update consistency (concurrent runs and writers, idempotency reservation and flow, in-flight `409` and
  stale keys, key check vs lock order, 24-hour cleanup, single-deposit failures such as overflow, scale — see
  ADR-0008); must be accepted before `POST /balance-updates` is implemented; completes the pending scenarios in
  `docs/testing.md` (P9–P12, S6); records that the idempotency rows have exactly two states (`V1__schema.sql`,
  stricter than ADR-0006's CHECK formula, as its prose describes); decides whether the schema must exclude `NaN`
  and infinite balances (known issue #8, ADR-0011)
