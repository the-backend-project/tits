# Conventions for coding agents

This file holds the conventions every contributor's coding agent follows in this repository. Claude Code reads it through
[CLAUDE.md](CLAUDE.md). Building, testing and the rest of the contributor workflow are described in
[CONTRIBUTING.md](CONTRIBUTING.md); read it before building or testing. Keep personal setup out of this file, such as the
GitHub account an agent uses, how tools get on the PATH, or Podman instead of Docker. It belongs in the agent's
user-level instructions or memory.

## Building and testing

- The libraries target Java 25, the latest LTS release (see [Java version](docs/modules.md#java-version)). A newer local
  JDK is fine for building, because the compiler targets release 25, but don't use language features or APIs newer than
  Java 25.
- Run the tests of the modules a change affects before opening a pull request. They need a database: SQL Server
  (`bin/start-mssql.sh`, port 11433) by default, or PostgreSQL (`bin/start-postgres.sh`, port 15432) with
  `-Ddatabase=pgsql-jdbc`, `pgsql-r2dbc` or `pgsql-vertx` and `-pl '!apps/hello-world'`. Check whether the port already
  answers before starting a database. The tests of the core module that need a database are run by the database modules.
- After adding or upgrading a dependency or plugin, record the checksums as described in
  [Dependency checksums](CONTRIBUTING.md#dependency-checksums), with the Maven version CI uses as well as the local one
  if they differ. Different Maven versions write `.mvn/checksums/checksums.sha256` in different orders, so sort it with
  `LC_ALL=C sort` afterwards to keep the diff to the added lines.

## Code

- Don't put `--` comments inside SQL strings or text blocks. The SQL is sent to the database as written, comments
  included. Explain the SQL with Java comments outside the string instead.

## Documentation

- Keep [docs](docs), the [README](README.md) and [CONTRIBUTING.md](CONTRIBUTING.md) in line with the change, in the same
  pull request. A new page in `docs` also needs an entry in `nav` in `zensical.toml`.
- When `docs` changed, do a strict build like CI with `bin/serve-docs.sh --build` and fix any warnings.

## Git and GitHub

- Work on an issue in a worktree of its own under `.claude/worktrees` (ignored by git), never by switching branches in
  the main checkout. The branch is named `issue-N-<slug>` after the issue.
- Rebase onto `main` before the first push, and merge `main` into the branch after that. Don't force-push a branch that
  has been pushed.
- Don't add `Co-Authored-By` trailers for coding agents to commit messages.
- Include `Closes #N` in the description of a pull request that resolves issue N. Say which documentation was updated, or
  why none needed to change.
- Add issues you create to the [project board](https://github.com/orgs/the-backend-project/projects/1), as Todo, if you
  have access to it.
- Write a bare `@name` in issues, pull requests and comments only to notify that user. Put code and placeholders such as
  `@me` in backticks.

## Skills

Claude Code has two skills for this repository in [.claude/skills](.claude/skills):

- `/fix N` takes issue N from the board to a pull request, following the conventions above. See
  [Working on issues with Claude Code](CONTRIBUTING.md#working-on-issues-with-claude-code).
- `/serve-docs` serves the documentation with live reload, or does a strict build with `--build`.
