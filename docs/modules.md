# Modules

| Module                                                                | Contains                                                                                       |
|-----------------------------------------------------------------------|------------------------------------------------------------------------------------------------|
| core                                                                  | The state machine, the builder of transition models, and the storage interface of databases    |
| [http](http-outbox.md#dispatching-a-request)                          | HTTP messages, HTTP clients and their data types, shared by http-inbox and http-outbox         |
| [database-mssql, database-pgsql-*](databases.md)                      | The SQL of a database and the driver it is reached through, one module per database and driver |
| [http-inbox](http-inbox.md)                                           | Inboxes: entity models receiving HTTP requests and routing them to the entities of an application |
| [http-outbox](http-outbox.md)                                         | Outboxes: entity models sending HTTP requests at least once or at most once                    |
| [backoffice](backoffice.md)                                           | A web application for users signed in with OpenID Connect to look into the entities of an application |
| [test-api](testing.md)                                                | An API for tests to look into and manipulate the database of a state machine                   |
| [diagram](diagrams.md)                                                | State diagrams and ER diagrams of a state machine's model                                      |
| [templates](cardpayments.md)                                          | Ready-made entity models of a domain, such as card payments                                    |

An application depends on core, the database module of its database, and http-inbox and http-outbox if it receives or
sends HTTP requests. Core knows nothing of HTTP: the HTTP messages and clients are in http, which http-inbox and
http-outbox depend on and pass on to the application. The [backoffice](backoffice.md) of an application is an
application of its own, which depends on the application and on backoffice. See the [apps](apps.md) for complete
applications.

Core exports only what applications use: the packages `com.github.thxmasj.backend.core` (the state machine and the
building of entity models) and `core.database` (the databases and their stores, and of those reached through SQL their
clients and `DataSourceBuilder`). The http module
exports `com.github.thxmasj.backend.http`: the HTTP messages (`HttpRequest`, `HttpResponse`), the HTTP clients
(`HttpClient`, `NettyHttpClient`, `AccessTokenSupplier`) and the data types of HTTP messages (`HttpDataType`). The
packages under `core.internal`, such as the storage of the state machine with its SQL and the steps of a transition, are
exported only to the other modules of the library, as is `http.internal` to http-inbox and http-outbox. A database
module exports only its `SqlDatabase`, and provides the storage of the state machine, with the SQL of its dialect, as a
service from an internal package (see [what a database module provides](databases.md#what-a-database-module-provides)). The internal package of database-pgsql-jdbc is exported to the other PostgreSQL modules, which
share it. To create a schema or look into a database without a state machine, use `SqlDatabase.createSchema` and the [test
API](testing.md).

## Java version

The libraries require Java 25, the latest long-term support (LTS) release, and are compiled for it. They use no
language features or APIs of later releases, so applications can stay on an LTS release, with its years of updates,
and can be compiled to native executables with [GraalVM](https://www.graalvm.org/), whose newest release is for
Java 25. The [apps](apps.md) are built that way.

The libraries are committed to stability. They move to a newer release of Java only when it is the next LTS release
and GraalVM supports it, never for a feature of a release in between. An application built on the libraries is not
forced onto a newer Java than it can run on.
