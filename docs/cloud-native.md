# Cloud native demo

The `demo` module is a hello world web application built with the HTTP inbox and the state machine. It aims at
following the principles of [the twelve-factor app](https://12factor.net/), is compiled with GraalVM to a native
executable which boots in a few tens of milliseconds, and is packaged in a container image built from `scratch`.

Every `GET /` creates a `Greeting` entity, whose transition to the `Greeted` state responds with a page saying hello.

## Running

Requires Docker (or Podman) with Compose. From the `demo` directory:

```shell
docker compose up --build
```

and visit [http://localhost:8080/](http://localhost:8080/). Compose starts a SQL Server database, provisions a
database and login for the demo, runs the schema migration as a one-off process, and then starts the application.

The image can also be built alone, from the repository root:

```shell
docker build -f demo/Dockerfile -t tits-demo .
```

The application without native compilation is an ordinary Maven module. `DemoTest` runs it against the test database
the build uses (see `bin/start-mssql.sh`).

### Reachability metadata

Native compilation depends on metadata about reflection and resources that static analysis cannot find. The demo
needs none of its own. The libraries ship metadata for what they use in
`META-INF/native-image/com.github.thxmasj/<artifact>/`, and Netty and Reactor ship their own. The state machine's
covers HikariCP, the SQL Server driver, and Jackson. The HTTP inbox's makes Netty initialize one class at run time
(see [Gaps](#gaps)).

To check the metadata after changing the libraries, add `--exact-reachability-metadata
-H:MissingRegistrationReportingMode=Warn` to the `native-image` command in the `Dockerfile`, run the demo, and look for
`MissingReflectionRegistrationError` and `MissingResourceRegistrationError` in its output. Probes for optional
service providers, such as SLF4J providers, and for native transports of other architectures are expected.

## The twelve factors

| Factor | How the demo follows it |
|---|---|
| I. Codebase | The demo lives in the same repository as the libraries, and the same image is deployed everywhere. |
| II. Dependencies | Dependencies are declared in `demo/pom.xml` and verified against pinned checksums. Maven and the GraalVM builder image are pinned by checksum and digest. The executable is statically linked except for glibc, which is copied into the image, so the image contains nothing it does not declare. |
| III. Config | All configuration is read from environment variables, see below. Credentials and the location of the database have no defaults. |
| IV. Backing services | The database is an attached resource, located by `DATABASE_HOST`, `DATABASE_PORT` and `DATABASE_NAME`. |
| V. Build, release, run | The `Dockerfile` is the build stage. An image combined with an environment is a release, which is run without modification. |
| VI. Processes | The process is stateless. Every request is handled by the state machine, which keeps all state in the database, so any process can handle any request. |
| VII. Port binding | The process serves HTTP by itself, with reactor-netty, on `$PORT`. |
| VIII. Concurrency | Scale out by running more processes against the same database. |
| IX. Disposability | The native executable is listening after a few tens of milliseconds. On `SIGTERM` it stops accepting requests and lets those in flight complete, then stops the state machine's workers once their ongoing work completes, and closes the database connection pool. |
| X. Dev/prod parity | Locally, Compose runs the same image against the same kind of database as other environments. |
| XI. Logs | Logs are written unbuffered to stdout, one line per event. |
| XII. Admin processes | The schema is created by `demo migrate`, run as a one-off process from the same image with the same configuration, but with credentials allowed to create the schema. |

### Configuration

| Variable | Default | Description |
|---|---|---|
| `PORT` | `8080` | Port to serve HTTP on |
| `DATABASE_HOST` | | SQL Server host |
| `DATABASE_PORT` | `1433` | SQL Server port |
| `DATABASE_NAME` | | Database name |
| `DATABASE_USER` | | Login |
| `DATABASE_PASSWORD` | | Password of the login |
| `DATABASE_TRUST_SERVER_CERTIFICATE` | `false` | Whether to skip validation of the server certificate. For local use only. |
| `DATABASE_POOL_SIZE` | `10` | Maximum number of database connections |
| `DATABASE_QUERY_TIMEOUT_SECONDS` | `10` | Query timeout |
| `DATABASE_SCHEMA` | `demo` | Schema holding the state machine's tables |
| `DATABASE_ROLE` | | Role to grant access to the schema. Required by `migrate` only. |

`GET /health` responds `200 OK` as long as the process is able to serve HTTP. It does not involve the database.

## Gaps

Building the demo revealed the following gaps in the libraries and their dependencies, some of which the libraries
work around.

### Native compilation

- **No GraalVM supports Java 27 yet.** The newest GraalVM is based on Java 25, while the libraries target Java 27.
  The image build therefore compiles the libraries with `-Dmaven.compiler.release=25`. This works as long as the
  libraries do not use language features or APIs newer than Java 25.
- **Jackson needs reflection.** The state machine marshals event data with Jackson, which accesses the data classes
  reflectively. Applications must provide reachability metadata for their own data classes. Event references, which the
  HTTP inbox stores with its events, use `DataType.eventReference()`, which needs no reflection.
- **Netty puts a direct buffer in the image heap.** Netty 4.1 initializes its classes at build time, but
  `CleartextHttp2ServerUpgradeHandler`, used by the reactor-netty HTTP server, holds a direct buffer, which fails the
  build with GraalVM 25. The HTTP inbox therefore initializes that class at run time. The setting could go away with a
  later Netty version.
- **Runtime dependencies the server does not use.** The state machine depends on Hibernate Validator, and reactor-netty
  brings QUIC and macOS DNS support. They end up on the runtime classpath of every application, lengthening native
  compilation. They could be optional, the way diagram generation with PlantUML is kept in the separate `diagram`
  module.
- **No fully static executable on arm64.** Fully static executables require musl, which the GraalVM builder images only
  support on amd64. The demo links glibc dynamically and copies it into the image, which works on both architectures.

### Configuration and backing services

- **Database connections are configured for Azure.** `DataSourceBuilder` always enables encryption and validates the
  server certificate against `*.database.windows.net`. Any other SQL Server must either have such a certificate or be
  trusted blindly with `DATABASE_TRUST_SERVER_CERTIFICATE`. The certificate host name should be configurable, and
  a connection URL, the common way to attach a backing service, is not supported.
- **SQL Server only.** The state machine supports no other database. Locally on arm64 the only option is Azure SQL
  Edge 1.0.7, which Microsoft has retired, while the build uses version 2.0.0, which is amd64-only, so local
  development and the build do not run the same database.

### Admin processes

- **Schema creation is coupled with the state machine.** The `StateMachine` constructor creates the schema when given a
  schema data source. To create the schema as a separate admin process, the demo calls `CreateSchema` directly, and has
  to compute its list of secondary id models the same way `StateMachine` does. If the two ever differ, the checksum of
  the schema does not match and the application fails to start. A public migration entry point taking the transition
  models would avoid this.
- **No schema evolution.** A schema that differs from what the code expects is rejected with a checksum mismatch.
  There is no way to migrate an existing schema to a new version.

### Logs

- **Unconditional debug output.** The libraries write to `System.out` directly in about 30 places, for example every
  state transition and every change stored. This output can not be turned off or leveled, and is mixed with the
  application's own log stream.
- **The `Listener` interface has no default methods.** Every application must implement all eleven methods to get
  structured events, even those it is not interested in.

### HTTP

- **Unroutable requests are bad requests.** The HTTP inbox responds with `400 Bad Request` to requests not matching
  any route, where `404 Not Found` or `405 Method Not Allowed` would be expected for a web page.
- **No readiness check.** Whether the state machine can reach its database is not exposed, so the demo's health check
  only tells whether the process serves HTTP. The demo implements it with an HTTP inbox listener filter, so it does not
  write to the database.
- **Every request is written to the database.** The HTTP inbox records every request as entities and events, even
  read-only requests like `GET /`, and there is no retention of these records.
