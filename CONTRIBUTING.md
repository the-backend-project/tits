# Contributing

Issues and pull requests are welcome. Work in progress is tracked on the
[project board](https://github.com/orgs/the-backend-project/projects/1). The conventions that coding agents follow are in
[AGENTS.md](AGENTS.md), and they apply to human contributors as well.

The project aims for stable, robust and long-lived systems, and it judges changes by that. Read the
[design principles](docs/principles.md) before proposing a larger change.

## Finding your way

### Where to start

* Issues labelled [good first issue](https://github.com/the-backend-project/tits/labels/good%20first%20issue) are
  small and self-contained. The Todo column of the [project board](https://github.com/orgs/the-backend-project/projects/1)
  lists what is planned next.
* To learn the code, read [Getting started](docs/getting-started.md) and the [architecture](docs/architecture.md), and
  then follow the hello world demo (`apps/hello-world`) from `HelloWorld.main` into the core module.
* For a bug, open an issue with a test or the steps that reproduce it. For a larger change, open an issue to agree on
  the approach before writing the code.

### Repository layout

| Path                                                | Contains                                                                         |
|-----------------------------------------------------|----------------------------------------------------------------------------------|
| [core](core)                                        | The state machine, `TransitionModelBuilder` and the interface of the databases   |
| [database-mssql](database-mssql), `database-pgsql-*` | The SQL and driver of each database, and the tests of core that need a database  |
| [http-inbox](http-inbox), [http-outbox](http-outbox) | Receiving and sending HTTP requests as entities                                  |
| [test-api](test-api)                                | The API for tests to look into and manipulate the database of a state machine    |
| [diagram](diagram)                                  | State diagrams and ER diagrams generated from a model                            |
| [doc-diagrams](doc-diagrams)                        | Generates the diagrams of the documentation, from the templates and the apps     |
| [templates](templates)                              | Ready-made models of a domain, such as card payments                             |
| [apps](apps)                                        | The identity provider and the hello world demo, compiled to native executables   |
| [deploy](deploy)                                    | Deployment of the apps to Azure Functions with Pulumi                            |
| [docs](docs)                                        | The documentation site, see [Documentation](#documentation)                      |
| [bin](bin), [test](test)                            | Scripts to start the test databases and serve the docs, and the databases' setup |
| [.mvn/checksums](.mvn/checksums)                    | The pinned checksums of all dependencies, see [below](#dependency-checksums)     |

The [Modules](docs/modules.md) page describes what an application depends on.

### What a pull request needs

* Tests of the changed behaviour, passing on the database modules the change affects.
* The documentation in [docs](docs), the [README](README.md) and this guide updated to match, in the same pull
  request.
* `Closes #N` in the description when it resolves issue N.

## Building

Requires Java 25 and Maven 3.9 or later. The libraries target the latest LTS release of Java, see
[Java version](docs/modules.md#java-version).

The tests need a database. They run on SQL Server by default, which can be started with `bin/start-mssql.sh`. To run
them on PostgreSQL, start it with `bin/start-postgres.sh` and run the build with
`-Ddatabase=pgsql-jdbc -pl '!apps/hello-world,!doc-diagrams'`, or with `-Ddatabase=pgsql-r2dbc` or
`-Ddatabase=pgsql-vertx` for its reactive drivers. The hello world demo runs on SQL Server only, and the doc-diagrams
module depends on it, so both are left out. The tests of the core module that
need a database are run by the database modules, each on its own database.

### Dependency checksums

Every artifact Maven resolves (dependencies and plugins) is verified against the SHA-256 checksums pinned in
`.mvn/checksums/checksums.sha256`, and the build fails on a mismatch. CI also fails on an artifact without a pinned
checksum; locally such artifacts are allowed, so IntelliJ IDEA can sync the project (it does not expand
`${maven.multiModuleProjectDirectory}` in `.mvn/maven.config` and therefore never finds the checksums file). To check
for unpinned artifacts locally like CI does, add `-Daether.artifactResolver.postProcessor.trustedChecksums.failIfMissing=true`.

After adding or upgrading a dependency or plugin, record the new checksums and commit the updated file:

```shell
mvn verify -Daether.artifactResolver.postProcessor.trustedChecksums.record=true
```

The set of resolved artifacts can differ between Maven versions, so record with the Maven version used in CI as well
as your local one if they differ.

## Documentation

The documentation site is built with [Zensical](https://zensical.org) from the Markdown in [docs](docs), with state
diagrams generated by the [doc-diagrams](doc-diagrams) module from the templates and the apps. To preview it locally
with live reload on http://localhost:8000:

```shell
bin/serve-docs.sh
```

This requires Graphviz (`dot`) and Python 3.10 or later in addition to the [build requirements](#building). Zensical is
installed into a `.venv` on first run. Set `PYTHON` to choose another interpreter, set `SKIP_DIAGRAMS=1` to reuse the
diagrams already generated in `docs/images`, or pass `--build` to do a strict build into `site` like CI does.

## Working on issues with a coding agent

The [fix](.agents/skills/fix/SKILL.md) skill takes a GitHub issue from the board to a pull request. It works with
[Claude Code](https://claude.com/claude-code) and [Mistral Vibe](https://github.com/mistralai/mistral-vibe), and needs
the [GitHub CLI](https://cli.github.com) (`gh`), logged in with your account. Run it with `/fix N` (or say "Fix #N"),
where N is the number of the issue. The agent then:

1. Assigns the issue to you (the user `gh` is logged in as) and moves it to In Progress on the board. Without write
   access to the repository it skips this step, and you work from a fork instead.
2. Creates the branch `issue-N-<slug>` off `main` in a worktree of its own, `.worktrees/issue-N-<slug>` (ignored by
   git), so the branch and local changes of the main checkout are left alone and several issues can be worked on at the
   same time. If a worktree or branch for the issue already exists, it continues there.
3. Presents its plan. If the task is large or the plan is not obvious, it waits for you to confirm or correct the plan
   before starting the work; a trivial fix goes ahead right away.
4. Makes the change and runs the tests of the affected modules, and updates `docs`, the README and this guide to match.
5. Merges or rebases what has landed on main, pushes the branch and opens a pull request that closes the issue.

The worktree stays in place for review changes. Once the pull request is merged, the agent can remove the worktree and
the local branch. In IntelliJ IDEA, mark `.worktrees` as excluded so the worktrees aren't indexed with the main
checkout.

The skills are in [.agents/skills](.agents/skills), and both agents read the project's conventions from
[AGENTS.md](AGENTS.md). The project's settings for each agent allow the common commands without asking: Maven (except
deploying), the scripts in [bin](bin), and the `git` and `gh` commands that only read.

- **Claude Code** reads AGENTS.md through [CLAUDE.md](CLAUDE.md), finds the skills through the symbolic link
  `.claude/skills`, and takes its settings from [.claude/settings.json](.claude/settings.json). Put your own
  permissions and other settings in `.claude/settings.local.json`, which git ignores.
- **Mistral Vibe** takes its settings from [.vibe/config.toml](.vibe/config.toml), which also lets commands run for
  up to an hour, because builds with tests take longer than Vibe's default of five minutes. Vibe loads the project's
  settings and skills only once you trust the folder. Put your own settings in `~/.vibe/config.toml`, and your own
  instructions, such as how tools get on the PATH, in `~/.vibe/AGENTS.md`. Vibe can't keep the docs server running, so
  `/serve-docs` only does the strict build there; run `bin/serve-docs.sh` yourself for live reload.
