# Documentation

| Where | What |
|---|---|
| [`architecture/overview.md`](architecture/overview.md) | Architecture overview (C4 levels 1–3, key flows, Mermaid diagrams) |
| [`architecture/data-model.md`](architecture/data-model.md) | Data model as it is today: tables, constraints, month-row lifecycle, Java mapping |
| [`../java/src/main/resources/openapi/openapi.yaml`](../java/src/main/resources/openapi/openapi.yaml) | API contract, contract-first ([ADR-0002](adr/0002-contract-first-openapi.md)–[ADR-0005](adr/0005-endpoint-scope.md), [ADR-0013](adr/0013-interest-periods.md)) |
| [`adr/`](adr/README.md) | Architecture Decision Records — why the system looks the way it does; open topics under *Planned decisions* |
| [`testing.md`](testing.md) | Testing guide: where a test goes, how to run the suites, acceptance scenarios |
| [`known-issues.md`](known-issues.md) | Known defects that are deliberately kept, e.g. in the legacy interest calculation |
| [`../AI_USAGE.md`](../AI_USAGE.md) | AI-assisted development setup |

**Reading order:** overview → data model → API contract → ADRs (index first) → testing guide.
