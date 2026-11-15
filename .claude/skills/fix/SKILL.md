---
name: fix
description: Start work on a GitHub issue. Use when the user says "Fix #N" (or invokes /fix N) — assigns the issue to the user and moves it to In Progress on the project board (if they have write access), creates a fresh branch off main in a worktree of its own, asks for confirmation of the plan unless the task is trivial, brings the documentation in line with the change, syncs with main, and opens a PR when the fix is complete.
argument-hint: <issue-number>
---

Start work on issue #$ARGUMENTS (or the number given in "Fix #N").

The repo is `the-backend-project/tits`; the board is
org project 1 ("The Backend"). The skill works for anyone who runs it with their own
`gh` login. Find out once what the user can do:

```sh
gh repo view the-backend-project/tits --json viewerPermission --jq .viewerPermission
git remote -v
```

- With `WRITE`, `MAINTAIN` or `ADMIN` the user can assign the issue, move it on the
  board and push branches to the repo. `<base>` below is the remote that points to
  `the-backend-project/tits` (usually `origin`), and branches are pushed there too.
- With less, the user works from a fork: skip steps 3 and 4 and say so. `<base>` is
  the remote that points to `the-backend-project/tits` (usually `upstream`; add it
  with `git remote add upstream https://github.com/the-backend-project/tits.git` if
  it is missing), and branches are pushed to the fork (usually `origin`).

1. **Check for earlier work on the issue.** The work happens in a worktree of its
   own (step 5), so local changes in the main checkout don't matter and must be
   left alone. Instead, look for a worktree or branch from an earlier start on the
   issue:

   ```sh
   git worktree list
   git branch --list 'issue-N-*'
   ```

   If one exists, continue in it instead of creating a new one (skip step 5), or
   ask the user if it is unclear whether that work should be kept.

2. **Read the issue:** `gh issue view N --json number,title,body,state,comments`.
   If it is closed, stop and tell the user.

3. **Assign it** to the user `gh` is logged in as:
   `gh issue edit N --add-assignee @me`

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
   These are the ids of the board, its Status field and the field's options. If the
   board is recreated, look them up with `gh project view 1 --owner
   the-backend-project --format json` and `gh project field-list 1 --owner
   the-backend-project --format json`. If the user can't write to the board, skip
   this step and say so.

5. **Branch off `<base>/main` in a new worktree.** Never switch branches in the main
   checkout: the user may have work in progress there, and other issues may be
   worked on at the same time. From the root of the main checkout, create the
   branch in a worktree under `.claude/worktrees` (ignored by git):

   ```sh
   git fetch <base>
   git worktree add --no-track -b issue-N-<slug> .claude/worktrees/issue-N-<slug> <base>/main
   cd .claude/worktrees/issue-N-<slug>
   ```

   `<slug>` is the issue title lowercased, non-alphanumerics replaced by `-`,
   trimmed to a few words (e.g. `issue-136-timeout-rows-not-deleted`).

   Do all the remaining steps in that worktree: edit files under its path and
   run git, Maven and the docs build there. When searching the main checkout,
   exclude `.claude/worktrees` so other issues' worktrees don't show up.

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
   git fetch <base>
   git rebase <base>/main   # branch not pushed yet
   git merge <base>/main    # branch already pushed: no force push
   ```

   If main moved, resolve any conflicts and run the tests of the affected modules
   again before pushing.

9. **Open a PR when the fix is complete.** Once the issue is resolved, the tests of
   the affected modules pass and the documentation is up to date, commit, sync with
   main (step 8), push the branch and open the PR without waiting to be asked:

   ```sh
   git push -u origin issue-N-<slug>
   gh pr create --repo the-backend-project/tits --title "<what the change does>" --body "Closes #N. ..."
   ```

   When working from a fork, push to the fork's remote and add
   `--head <fork-owner>:issue-N-<slug>` to `gh pr create`.

   Include `Closes #N` in the body so that merging closes the issue. Tell the user
   the PR's URL. If the fix is not complete, for example because a question is still
   open or tests fail, don't open a PR. Tell the user what is left instead.

   Leave the worktree in place after opening the PR, since review changes are
   made there. Once the PR is merged, remove it and the local branch from the
   root of the main checkout:

   ```sh
   git worktree remove .claude/worktrees/issue-N-<slug>
   git branch -D issue-N-<slug>
   ```
