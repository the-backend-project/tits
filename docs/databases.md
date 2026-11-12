# Databases

The state machine stores its [event logs](data-model.md) in SQL Server (including Azure SQL) or PostgreSQL. The SQL of
each database and the driver it is reached through are in a module of their own, and an application depends on the one
it uses:

| Module                                               | Database                      | Driver                                    | Connection pool              |
|------------------------------------------------------|-------------------------------|-------------------------------------------|------------------------------|
| [database-mssql](../database-mssql)                  | SQL Server, Azure SQL         | JDBC (Microsoft JDBC Driver)              | HikariCP                     |
| [database-pgsql](../database-pgsql)                  | PostgreSQL                    | JDBC (PgJDBC)                             | HikariCP                     |
| [database-pgsql-reactive](../database-pgsql-reactive) | PostgreSQL                    | R2DBC (r2dbc-postgresql)                  | r2dbc-pool                   |
| [database-pgsql-vertx](../database-pgsql-vertx)      | PostgreSQL                    | Vert.x PostgreSQL client                  | The Vert.x client's own pool |

The three PostgreSQL modules run the same SQL. The reactive ones reuse the queries of database-pgsql, and create the
schema through its JDBC driver, as [schema creation](schema-creation.md) is done once at startup and does not need to be
reactive. They differ in how the queries reach the database.

## Choosing a module

* **SQL Server or Azure SQL:** database-mssql, the only one for them. It is also the only one the
  [cloud native demo](cloud-native.md) and its Azure deployment are made for.
* **PostgreSQL:** database-pgsql, unless there is a reason to avoid blocking drivers. It is the most mature driver, and
  stores a transition in a single round trip.
* **PostgreSQL without blocking:** database-pgsql-vertx, which also stores a transition in a single round trip.
* **database-pgsql-reactive** is mainly for comparison. It stores a transition in several round trips, as explained
  below, which makes it the slowest of the PostgreSQL modules when the database is not close by.

## Storing a transition

The changes of a transition, i.e. its new events and triggers, are stored atomically with several SQL statements. How
many round trips to the database this takes depends on the driver:

* **database-mssql** sends the statements in one batch, with an explicit transaction inside it.
* **database-pgsql** sends them in one execution. PgJDBC sends each statement separately, but all at once, and
  PostgreSQL runs them in a single implicit transaction, which is rolled back if any of them fails.
* **database-pgsql-reactive** runs them one at a time between `BEGIN` and `COMMIT`. R2DBC runs one statement with
  parameters at a time, and r2dbc-postgresql does not send a statement before the one before it has completed, even
  when they are executed at once.
* **database-pgsql-vertx** sends `BEGIN`, the statements and `COMMIT` at once, on one connection. The Vert.x client
  pipelines them, i.e. sends each without waiting for the results of the ones before it. If a statement fails,
  PostgreSQL ignores the ones after it and turns the `COMMIT` into a rollback.

The round trips were measured with a ChangeState-like change of three inserts and a delete, through a proxy delaying
each direction by 10 ms, on a single connection:

| Module                  | Median time | Round trips |
|-------------------------|-------------|-------------|
| database-pgsql          | 27 ms       | 1           |
| database-pgsql-vertx    | 25 ms       | 1           |
| database-pgsql-reactive | 168 ms      | 6           |

This shows the effect of the round trips only, and is not a measure of throughput under load. With the database on
the same host the difference is small, but it grows with the latency to the database.

## Threads

* **database-mssql and database-pgsql** run each query on a virtual thread of its own, as the JDBC drivers block. A
  query holds a pooled connection while it runs.
* **database-pgsql-reactive** runs the queries on the event loop threads of Reactor Netty, which the state machine's HTTP
  clients and servers also use. The state machine's processing of the results continues on those threads.
* **database-pgsql-vertx** runs the queries on the event loop threads of a Vert.x instance of its own, next to those of
  Reactor Netty. The state machine's processing of the results continues on them.

## Pros and cons

### database-mssql

Pros:

* The only module for SQL Server and Azure SQL, and the one the demo and its deployment use.
* A transition is stored in a single round trip.
* Its queries name the indexes to use with hints, rather than leaving the choice to the query optimiser.
* The connection pool retries the first connection for 90 seconds, so a service waits for a database that is still
  starting.

Cons:

* The driver blocks, so each query takes a virtual thread while it runs.
* Connections are configured for Azure: encryption is always on, and the server certificate is validated against
  `*.database.windows.net` unless it is trusted blindly (see [cloud native](cloud-native.md)).

### database-pgsql

Pros:

* A transition is stored in a single round trip.
* PgJDBC is the most widely used and mature PostgreSQL driver.
* The connection pool retries the first connection for 90 seconds, as for SQL Server.

Cons:

* The driver blocks, so each query takes a virtual thread while it runs.
* PostgreSQL has no index hints, so the choice of indexes is left to the planner and its statistics.

### database-pgsql-reactive

Pros:

* Non-blocking, on the event loops of Reactor Netty, which the state machine uses already.
* R2DBC is a standard API built on Reactive Streams, which fits the state machine's Reactor pipeline.
* Few dependencies of its own: the R2DBC SPI, the driver, its pool and the SCRAM authentication.

Cons:

* A transition takes a round trip for each statement, plus `BEGIN` and `COMMIT`, as R2DBC runs one statement with
  parameters at a time and the driver does not pipeline them.
* The driver is built against older versions of Reactor and Netty, so the build pins `reactor-netty-core` and
  `reactor-pool` to the versions of the Reactor BOM the state machine uses.
* There is no retry of the first connection at startup.
* SQL of several statements is split at semicolons at the end of a line, which the statements must follow.
* The schema is still created through JDBC, so the JDBC driver is needed too.

### database-pgsql-vertx

Pros:

* Non-blocking, and a transition is stored in a single round trip, as the statements are pipelined.

Cons:

* A Vert.x instance with event loop threads of its own runs next to Reactor Netty, and its futures are converted to
  Reactor.
* More dependencies: Vert.x core and its SQL client, and Jackson 2 next to the state machine's Jackson 3. The build pins
  Netty to the version of Reactor Netty, and the SCRAM authentication to the version of Vert.x.
* Statements are only pipelined when prepared statements are cached, which the module turns on. Each connection keeps
  the statements it has prepared.
* A connection is held for the whole of a transition's changes.
* The client leaves the types of parameters to PostgreSQL, which infers each from where it is first used. A parameter
  used where its type is ambiguous must be cast, as the time in the query of the next delayed trigger is.
* There is no retry of the first connection at startup.
* SQL of several statements is split at semicolons at the end of a line, as for R2DBC, and the schema is created
  through JDBC.

## Using a module

With a JDBC module, the state machine is given a data source, built from the module's `Database`. The state machine
finds the database behind it among the modules on the module path or class path:

```java
DataSource dataSource = new DataSourceBuilder(new PgsqlDatabase(), config).buildPooled();
DataSource schemaDataSource = new DataSourceBuilder(new PgsqlDatabase(), adminConfig).build();
new StateMachine(delayer, transitions, dataSource, schemaDataSource, schemaName, role, clock, listener);
```

With a reactive module, the state machine is given the module's `Database` and a client of it, which has a connection
pool of its own. The schema data source is a JDBC one, from the same `Database`:

```java
Database database = new PgsqlVertxDatabase();
new StateMachine(
    delayer,
    transitions,
    database,
    database.client(config),
    database.dataSource(adminConfig),
    schemaName,
    role,
    clock,
    listener,
    true
);
```

The state machine closes the data source or client, and with it the connection pool, when it is closed.

## Testing

The tests run on SQL Server by default. To run them on PostgreSQL, run the build with `-pl '!demo'` and
`-Ddatabase=pgsql`, `-Ddatabase=pgsql-reactive` or `-Ddatabase=pgsql-vertx`. The tests of the core module that need a
database are run by the module of the database, and the other modules' tests use it too. See the
[README](https://github.com/the-backend-project/tits#building).
