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

Report findings ranked by severity. Do not change any files.
