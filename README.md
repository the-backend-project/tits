# The Integratable Transaction Server

An event-sourcing library for Java. You model your domain as state machines in plain Java, following domain-driven
design. The library appends every event to an immutable event log, takes the transitions in ACID transactions with
optimistic concurrency, deduplicates incoming HTTP requests, and sends outgoing ones through a transactional outbox.
It is reactive and stateless, runs on the JVM or as a GraalVM native image, and keeps everything in a database,
with no framework or message broker.

Documentation is available at https://the-backend-project.github.io/tits/:

* [Getting started](docs/getting-started.md): add the libraries, declare a model, run it and test it.
* [Design principles](docs/principles.md): what the project optimises for, and why.
* [Architecture](docs/architecture.md): how the parts fit together.

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

See [CONTRIBUTING.md](CONTRIBUTING.md) for where to start and how the repository is laid out, how to build the project,
run the tests on each database, pin dependency checksums and preview the documentation. Issues can be taken from the
board to a pull request with an agentic workflow, where a coding agent follows the project's conventions in
[AGENTS.md](AGENTS.md).
