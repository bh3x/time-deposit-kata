---
description: Push the current branch and open a PR into main (invoking /ship approves the push and the PR)
allowed-tools: Bash(git status:*), Bash(git log:*), Bash(git push -u origin:*), Bash(gh pr create --repo bh3x/time-deposit-kata:*)
---
!`git status --short --branch`
!`git log main..HEAD --oneline`

1. Confirm the tree is clean, all commits are approved, and `/review` has been run with findings fixed or
   listed as Follow-ups.
2. Write the PR title and a short description (what / why / how verified / **Follow-ups** — out-of-scope
   findings with the step they belong to, or "none"), ending with the Claude Code footer from CLAUDE.md.
3. Without asking again — invoking `/ship` is the approval — push with `git push -u origin <branch>` and open the PR
   with `gh pr create --repo bh3x/time-deposit-kata --base main --head <branch>`, never against the upstream
   `ikigai-digital` repository. If step 1 fails, stop and report instead. Show the PR link and description after.
