---
description: Push the current branch and open a PR into main (push and PR always require approval)
allowed-tools: Bash(git status:*), Bash(git log:*)
---
!`git status --short --branch`
!`git log main..HEAD --oneline`

1. Confirm the tree is clean and all commits are approved.
2. Propose the PR title and a short description (what / why / how verified),
   ending with the Claude Code footer from CLAUDE.md.
3. STOP and wait for approval. Then push with `git push -u origin <branch>` and open the PR with
   `gh pr create --repo bh3x/time-deposit-kata --base main` — never against the upstream `ikigai-digital` repository.
   Both commands are intentionally not pre-approved, so each one prompts for confirmation.
