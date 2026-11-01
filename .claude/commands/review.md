---
description: Review the current changes against the project rules
argument-hint: [optional scope, e.g. a path or topic]
allowed-tools: Bash(git diff:*), Bash(git status:*), Read, Grep, Glob, Agent
---
Use the `code-reviewer` subagent to review the changes below (committed on the branch, staged, unstaged and untracked).
Scope: $ARGUMENTS

Committed on this branch:
!`git diff main...HEAD --stat`
Staged:
!`git diff --cached --stat`
Unstaged:
!`git diff --stat`
Working tree (incl. untracked):
!`git status --short --untracked-files=all`

The reviewer works on its own until it can present its findings: it asks for no permission or decision, verifies
with the allowed command forms (build and tests incl. Docker, contract lint, smoke run as needed) and reports anything
it could not check as "not run". Do not change any files.
Report findings ranked by severity.
