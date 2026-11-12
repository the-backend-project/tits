# Apps

The apps are complete applications built with the HTTP inbox and the state machine. Both are compiled with GraalVM to
native executables, packaged in container images built from `scratch`, and run on SQL Server with Docker Compose.

| App                                       | Module                   | What it does                                                                    |
|-------------------------------------------|--------------------------|---------------------------------------------------------------------------------|
| [Identity provider](identity-provider.md) | `apps/identity-provider` | An OAuth 2.0 authorization server for the client credentials grant              |
| [Hello world demo](hello-world.md)        | `apps/hello-world`       | A hello world web application following the principles of the twelve-factor app |

Both can be [deployed to Azure Functions](azure-functions.md) with a serverless Azure SQL database by the `deploy`
module, as can any other application built the same way.

See the [architecture](architecture.md) for the parts the apps are built from.
