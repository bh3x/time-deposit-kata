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
   Remind the user to pull `main` if it may be behind `origin/main`.
2. Re-read the relevant rules in CLAUDE.md.
3. Present: goal, key terms with ambiguous readings and the assumptions/ADRs needed, files to create/change,
   tests to add, proposed commit(s) (Conventional Commits).
4. STOP and wait for explicit approval. Do not create the branch or change files yet.
