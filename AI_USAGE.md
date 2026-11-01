# AI-Assisted Development

## Tools
- **Claude Code** (VS Code extension) — agentic coding assistant
- **Model:** Claude Opus 5.5 (`claude-opus-5-5`)

## Setup
| File | Purpose |
|---|---|
| [`CLAUDE.md`](CLAUDE.md) | Project rules loaded by Claude Code: context, hard constraints, architecture, git workflow |
| [`.claude/settings.json`](.claude/settings.json) | Shared permissions: read-only shell and git commands, Gradle builds, branching, commits, pushes and PR creation allowed (each commit is still approved in chat, `CLAUDE.md` rule 2); merges, rebases, resets, PR merge/close, remote branch deletion and `docker compose` ask first; force pushes, recursive deletes, `git checkout --` and `.env` reads denied |
| `.claude/settings.local.json` | Personal overrides (gitignored) |
| [`.claude/commands/`](.claude/commands/) | Workflow slash commands: `/step` (start an increment), `/review` (review the diff), `/ship` (push and open a PR) |
| [`.claude/agents/code-reviewer.md`](.claude/agents/code-reviewer.md) | Read-only reviewer subagent that checks changes against `CLAUDE.md` |
| [`.claude/agents/test-designer.md`](.claude/agents/test-designer.md) | Read-only subagent that derives test cases from the requirements before implementation (ADR-0009) |

## How to reproduce
1. Install [Claude Code](https://docs.claude.com/en/docs/claude-code) (CLI or VS Code extension).
2. Open this repository; `CLAUDE.md` and `.claude/settings.json` are picked up automatically.

## Workflow
- Plan first, then implement in small increments — one branch and pull request per increment.
- Every step is planned and approved before work starts; commits, pushes and PRs are approved individually.
- Decisions are made through structured options with a recommendation; design is recorded in ADRs before
  implementation; each increment is reviewed by a read-only reviewer subagent.
- Characterization tests pin the legacy behaviour before any refactoring.
- AI-assisted commits carry the trailer `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## AI-assisted parts
Almost all artefacts were produced with Claude Code, under human direction; nothing was merged without human
approval.

| Part | AI | Human |
|---|---|---|
| Requirements analysis, readings of ambiguous terms | Drafted, with options and a recommendation | Chose every reading |
| Design (ADR-0001…0012, OpenAPI contract, schema) | Drafted the ADRs and alternatives; checked facts in official docs | Decided each option; accepted each ADR by merging |
| Tests | `test-designer` derived cases from the requirements before coding; Claude wrote them red first | Reviewed the proposed test cases and their implementation; approved each plan |
| Implementation and refactoring (calculator, interest policies, persistence, use cases, REST, Docker) | Wrote the code in small increments | Contributed suggestions and proposed approaches; approved each commit |
| Reviews | `code-reviewer` reviewed the increments against `CLAUDE.md` and the ADRs before they were shipped | Reviewed the codebase continuously throughout; decided what to fix now and what to record as a follow-up |
| Git history | Branches, Conventional Commits, PR descriptions | Merged every pull request |

**Why:** the brief asks for an AI-driven workflow; under a short deadline it made it possible to cover design,
tests and documentation in depth, and the rules in `CLAUDE.md` kept the output consistent across sessions.

**Safeguards** that make AI-written code checkable rather than trusted: characterization tests, a frozen copy of the
legacy calculator and a randomized comparison pin the legacy behaviour; ArchUnit enforces the hexagonal rules; new
behaviour is test-first (the failing test is shown before the code); mutation probes (deliberately broken code) show
that tests catch mistakes, also where a test could not be written first; review findings were fixed or listed as
follow-ups in the pull requests, and reports state exactly what was run.
