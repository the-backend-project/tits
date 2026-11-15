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

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for how to build the project, run the tests on each database, pin dependency
checksums, preview the documentation and work on issues with Claude Code.
