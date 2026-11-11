---
name: fix
description: Start work on a GitHub issue. Use when the user says "Fix #N" (or invokes /fix N) — assigns the issue, moves it to In Progress on the project board, and creates a fresh branch off origin/main.
argument-hint: <issue-number>
---

Start work on issue #$ARGUMENTS (or the number given in "Fix #N").

Use `/opt/homebrew/bin/gh`. The repo is `the-backend-project/tits`; the board is
org project 1 ("The Backend").

1. **Check the working tree.** If `git status --porcelain` is not empty, stop and
   ask the user what to do with the local changes.

2. **Read the issue:** `gh issue view N --json number,title,body,state,comments`.
   If it is closed, stop and tell the user.

3. **Assign it:** `gh issue edit N --add-assignee thxmasj-claude`

4. **Move it to In Progress.** `item-add` is idempotent and returns the existing
   item if the issue is already on the board:

   ```sh
   ITEM=$(gh project item-add 1 --owner the-backend-project \
     --url https://github.com/the-backend-project/tits/issues/N --format json --jq .id)
   gh project item-edit --id "$ITEM" \
     --project-id PVT_kwDOCtCyDc4An_-_ \
     --field-id PVTSSF_lADOCtCyDc4An_-_zgfpLXU \
     --single-select-option-id 47fc9ee4
   ```

   Status option IDs: Todo `f75ad846`, In Progress `47fc9ee4`, Done `98236657`.

5. **Branch off origin/main:**

   ```sh
   git fetch origin
   git switch --no-track -c issue-N-<slug> origin/main
   ```

   `<slug>` is the issue title lowercased, non-alphanumerics replaced by `-`,
   trimmed to a few words (e.g. `issue-136-timeout-rows-not-deleted`).

6. **Start working** on the issue. Briefly tell the user the branch name and your
   plan, then proceed.
