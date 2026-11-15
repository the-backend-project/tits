# The Integratable Transaction Server

Documentation is available at https://the-backend-project.github.io/tits/.

The [apps](apps) are applications built with the HTTP inbox and the state machine, compiled to native executables:

* The [identity provider](apps/identity-provider) is an OAuth 2.0 authorization server for the client credentials
  grant. See [Identity provider](docs/identity-provider.md).
* The [hello world demo](apps/hello-world) is a web application following the principles of the twelve-factor app. See
  [Hello world demo](docs/hello-world.md).

The [deploy](deploy) module deploys either of them, or any application built the same way, to Azure Functions with a
serverless Azure SQL database. See [Deploying to Azure Functions](docs/azure-functions.md).

The [test-api](test-api) module is an API for tests to look into and manipulate the database of a state machine.
See [Testing](docs/testing.md).

## Databases

The state machine stores its event logs in SQL Server (including Azure SQL) or PostgreSQL. The SQL and JDBC driver of
each are in a module of their own, [database-mssql](database-mssql) and [database-pgsql-jdbc](database-pgsql-jdbc), and
an application depends on the one for its database. The `StateMachine` finds it on the module path or class path, by the
database behind the `DataSource` it is given. To build a data source, give `DataSourceBuilder` the module's `Database`,
e.g. `new DataSourceBuilder(new PgsqlDatabase(), config)`. The [hello world demo](apps/hello-world) runs on SQL Server only.

PostgreSQL can also be used without blocking, through the R2DBC driver with [database-pgsql-r2dbc](database-pgsql-r2dbc)
or through the Vert.x client with [database-pgsql-vertx](database-pgsql-vertx). A state machine is then given the
module's `Database` and a `DatabaseClient` from its `client(config)`, which has a connection pool of its own, instead of
a data source. See [Databases](docs/databases.md) for the pros and cons of each module.

## Building

Requires Java 25 and Maven 3.9 or later. The libraries target the latest LTS release of Java, see
[Java version](docs/modules.md#java-version).

The tests need a database. They run on SQL Server by default, which can be started with `bin/start-mssql.sh`. To run
them on PostgreSQL, start it with `bin/start-postgres.sh` and run the build with `-Ddatabase=pgsql-jdbc -pl '!apps/hello-world'`, or
with `-Ddatabase=pgsql-r2dbc` or `-Ddatabase=pgsql-vertx` for its reactive drivers. The tests of the core module that
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
diagrams generated from the [templates](templates) module. To preview it locally with live reload on
http://localhost:8000:

```shell
bin/serve-docs.sh
```

This requires Graphviz (`dot`) and Python 3.10 or later in addition to the build requirements above. Zensical is
installed into a `.venv` on first run. Set `PYTHON` to choose another interpreter, set `SKIP_DIAGRAMS=1` to reuse the
diagrams already generated in `docs/images`, or pass `--build` to do a strict build into `site` like CI does.

## Working on issues with Claude Code

The [fix](.claude/skills/fix/SKILL.md) skill takes a GitHub issue from the board to a pull request. Run it in Claude
Code with `/fix N` (or say "Fix #N"), where N is the number of the issue. Claude then:

1. Assigns the issue to `thxmasj-claude` and moves it to In Progress on the board.
2. Creates the branch `issue-N-<slug>` off `origin/main` in a worktree of its own, `../state-machine-issue-N-<slug>`,
   so the branch and local changes of the main checkout are left alone and several issues can be worked on at the same
   time. If a worktree or branch for the issue already exists, it continues there.
3. Presents its plan. If the task is large or the plan is not obvious, it waits for you to confirm or correct the plan
   before starting the work; a trivial fix goes ahead right away.
4. Makes the change and runs the tests of the affected modules, and updates `docs` and this README to match.
5. Merges or rebases what has landed on main, pushes the branch and opens a pull request that closes the issue.

The worktree stays in place for review changes. Once the pull request is merged, Claude can remove the worktree and the
local branch.
