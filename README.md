# The Integratable Transaction Server

Documentation is available at https://the-backend-project.github.io/tits/.

The [demo](demo) module is a hello world web application following the principles of the twelve-factor app, compiled
to a native executable. See [Cloud native demo](docs/cloud-native.md). The [deploy](deploy) module deploys it, or any
application built the same way, to Azure Functions with a serverless Azure SQL database. See
[Deploying to Azure Functions](docs/azure-functions.md).

The [test-api](test-api) module is an API for tests to look into and manipulate the database of a state machine.
See [Testing](docs/testing.md).

## Databases

The state machine stores its event logs in SQL Server (including Azure SQL) or PostgreSQL. The SQL and JDBC driver of
each are in a module of their own, [database-mssql](database-mssql) and [database-pgsql](database-pgsql), and an
application depends on the one for its database. The `StateMachine` finds it on the module path or class path, by the
database behind the `DataSource` it is given. To build a data source, give `DataSourceBuilder` the module's `Database`,
e.g. `new DataSourceBuilder(new PgsqlDatabase(), config)`. The [demo](demo) runs on SQL Server only.

PostgreSQL can also be used without blocking, through the R2DBC driver with
[database-pgsql-reactive](database-pgsql-reactive) or through the Vert.x client with
[database-pgsql-vertx](database-pgsql-vertx). A state machine is then given the module's `Database` and a `Client` from
its `client(config)`, which has a connection pool of its own, instead of a data source. See
[Databases](docs/databases.md) for the pros and cons of each module.

## Building

Requires Java 27 and Maven 3.9 or later.

The tests need a database. They run on SQL Server by default, which can be started with `bin/start-mssql.sh`. To run
them on PostgreSQL, start it with `bin/start-postgres.sh` and run the build with `-Ddatabase=pgsql -pl '!demo'`, or
with `-Ddatabase=pgsql-reactive` or `-Ddatabase=pgsql-vertx` for its reactive drivers. The tests of the core module that need a database
are run by the database modules, each on its own database.

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
