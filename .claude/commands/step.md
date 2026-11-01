---
description: Plan a new increment, then wait for approval
argument-hint: <branch-name> <goal>
allowed-tools: Bash(git status:*), Bash(git log:*), Read, Grep, Glob
---
Current state:
!`git status --short --branch`
!`git log --oneline -5`

Plan a new increment: $ARGUMENTS

1. Verify the working tree is clean and we are on the local `main`; if not, stop and report.
   Remind the user to pull `main` if it may be behind `origin/main`. Then create the increment branch from `main`
   (`git checkout -b <branch-name>`), so all planning happens on it.
2. Re-read the relevant rules in CLAUDE.md.
3. For any increment that adds or changes tests or production code, have the `test-designer` subagent derive
   the test cases from the requirements (ADR-0009, `docs/testing.md`) without seeing the implementation; use its
   case list for "tests to add" and explain any case you drop or change.
4. Present: goal, key terms with ambiguous readings and the assumptions/ADRs needed, files to create/change,
   tests to add, proposed commit(s) (Conventional Commits).
5. STOP and wait for explicit approval. Do not change files yet; if the plan is rejected, delete the branch.
