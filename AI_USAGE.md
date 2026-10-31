# AI-Assisted Development

## Tools
- **Claude Code** (VS Code extension) — agentic coding assistant
- **Model:** Claude Opus 5.5 (`claude-opus-5-5`)

## Setup
| File | Purpose |
|---|---|
| [`CLAUDE.md`](CLAUDE.md) | Project rules loaded by Claude Code: context, hard constraints, architecture, git workflow |
| [`.claude/settings.json`](.claude/settings.json) | Shared permissions: read-only git commands and Maven builds allowed; git changes, pushes and PRs require confirmation; common force-push, recursive-delete and `.env`-read patterns denied |
| `.claude/settings.local.json` | Personal overrides (gitignored) |
| [`.claude/commands/`](.claude/commands/) | Workflow slash commands: `/step` (start an increment), `/review` (review the diff), `/ship` (push and open a PR) |
| [`.claude/agents/code-reviewer.md`](.claude/agents/code-reviewer.md) | Read-only reviewer subagent that checks changes against `CLAUDE.md` |

## How to reproduce
1. Install [Claude Code](https://docs.claude.com/en/docs/claude-code) (CLI or VS Code extension).
2. Open this repository; `CLAUDE.md` and `.claude/settings.json` are picked up automatically.

## Workflow
- Plan first, then implement in small increments — one branch and pull request per increment.
- Every change is proposed, reviewed and explicitly approved before it is written or committed.
- Characterization tests pin the legacy behaviour before any refactoring.
- AI-assisted commits carry the trailer `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## AI-assisted parts
_To be completed at the end of the assignment._
