---
name: fix
description: Start work on a GitHub issue. Use when the user says "Fix #N" (or invokes /fix N) — assigns the issue, moves it to In Progress on the project board, creates a fresh branch off origin/main, asks for confirmation of the plan unless the task is trivial, brings the documentation in line with the change, syncs with main, and opens a PR when the fix is complete.
argument-hint: <issue-number>
---

Start work on issue #$ARGUMENTS (or the number given in "Fix #N").

The repo is `the-backend-project/tits`; the board is
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

6. **Plan, and confirm the plan if it needs it.** Tell the user the branch name
   and your plan: what you will change, where, and how you will test it. Then
   decide whether the plan needs confirmation before you spend effort on it:

   - **Ask first** when the task is large or the plan is not obvious: it touches
     several modules or public API, changes the database schema or behaviour users
     rely on, involves a design choice the issue leaves open, or the issue can be
     read more than one way. Stop and ask the user to confirm or correct the plan
     (with `AskUserQuestion` when the choice is between concrete alternatives), and
     don't start the work until they answer.
   - **Proceed** when the task is trivial and the plan is obvious from the issue,
     for example a small, local fix with one reasonable solution. Say that you are
     going ahead without confirmation.

   If the work turns out to be bigger or different than planned, stop and confirm
   the new plan before continuing.

7. **Make the documentation match the change.** Before pushing, check that the
   documentation describes what the branch does now:

   - Look up what the change touches (`git diff origin/main --stat`) and grep
     `docs/*.md`, `README.md` and the Javadoc of the touched public types for the
     classes, methods, configuration, endpoints, tables and behaviour involved.
   - Fix whatever the change made wrong or out of date, including code samples,
     names and defaults that were renamed or removed. Document new behaviour that
     users of the library need to know about, on the page that already covers that
     area. A new page also needs an entry in `nav` in `zensical.toml`.
   - If the docs changed, do a strict build like CI with `/serve-docs --build` and
     fix any warnings.

   Commit the documentation together with the change it describes. In the PR
   description, name the pages that were updated, or say why no documentation
   needed to change.

8. **Sync with main before every push.** Before pushing the branch, whether to open
   the PR or to update it, bring in what has landed on main since:

   ```sh
   git fetch origin
   git rebase origin/main   # branch not pushed yet
   git merge origin/main    # branch already pushed: no force push
   ```

   If main moved, resolve any conflicts and run the tests of the affected modules
   again before pushing.

9. **Open a PR when the fix is complete.** Once the issue is resolved, the tests of
   the affected modules pass and the documentation is up to date, commit, sync with
   main (step 8), push the branch and open the PR without waiting to be asked:

   ```sh
   git push -u origin issue-N-<slug>
   gh pr create --title "<what the change does>" --body "Closes #N. ..."
   ```

   Include `Closes #N` in the body so that merging closes the issue. Tell the user
   the PR's URL. If the fix is not complete, for example because a question is still
   open or tests fail, don't open a PR. Tell the user what is left instead.
