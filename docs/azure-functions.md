# Deploying to Azure Functions

The `deploy` module is a [Pulumi](https://www.pulumi.com) program written in Java, which deploys the
[cloud native demo](cloud-native.md), or any other application built the same way, to
[Azure Functions](https://learn.microsoft.com/azure/azure-functions/) on the Flex Consumption plan with a serverless
Azure SQL database. Both scale to zero: you pay for the function app only while it handles requests, and for the
database's compute only while it has connections.

## What is deployed

All resources are created in a resource group of their own:

- An Azure SQL server with a serverless database, and firewall rules letting through Azure services and the deploying
  machine. The server administrator's password and the application's password are generated.
- A function app on the Flex Consumption plan, with a storage account for the Functions host and the deployment
  package, which the app accesses with its managed identity.
- Application Insights with a Log Analytics workspace, which collects what the application writes to stdout.

Deploying then runs three steps on the deploying machine:

1. Provisions the database: a contained database user for the application, and the role which the migrate admin
   process grants access to the schema. This is the counterpart of `demo/database/init.sql` for local runs.
2. Runs the migrate admin process from the application's image, with the server administrator's credentials.
3. Deploys the application to the function app.

Each step runs again when its inputs change, so a new image is migrated and deployed by running `pulumi up` again.

The application runs as a [custom handler](https://learn.microsoft.com/azure/azure-functions/functions-custom-handlers),
a web server to which the Functions host proxies every request. Flex Consumption does not run container images, so
the deployment package is built from the file system of the image: the executable along with the C library it is
linked with, and a launcher script which passes the port the Functions host chooses on to the application in `PORT`.

## Requirements

- Java 27 and Maven 3.9 or later, as for the rest of the build
- [Docker](https://docs.docker.com/get-docker/), to build the image and run the migrate admin process
- The [Pulumi CLI](https://www.pulumi.com/docs/iac/download-install/)
- The [Azure CLI](https://learn.microsoft.com/cli/azure/install-azure-cli), logged in with `az login` to a subscription
  where you may create role assignments, for example as Owner. Pulumi uses the same login.

## Deploying the demo

Build the image for the platform Azure Functions runs on, which on an arm64 machine is emulated and takes a while:

```shell
docker build --platform linux/amd64 -f demo/Dockerfile -t tits-demo .
```

Build the Pulumi program:

```shell
mvn package -pl deploy -am -DskipTests
```

Then, from the `deploy` directory, create a stack, configure it, and deploy:

```shell
pulumi stack init demo
pulumi config set azure-native:location northeurope
pulumi config set image tits-demo
pulumi config set databaseSchema demo
pulumi up
```

Choose a [region supporting Flex Consumption](https://learn.microsoft.com/azure/azure-functions/flex-consumption-how-to#view-currently-supported-regions).
When done, `pulumi stack output url` is the address of the demo. `pulumi destroy` deletes everything again.

## Deploying another application

Another application is deployed the same way, as long as it follows the conventions of the demo:

- It is a container image for `linux/amd64` with an entry point which serves HTTP on `$PORT`, built from `scratch` or
  otherwise holding only what the executable needs. The executable is linked statically, or dynamically against a C
  library in the image.
- It reads the location of its database and its credentials from `DATABASE_HOST`, `DATABASE_NAME`, `DATABASE_USER`
  and `DATABASE_PASSWORD`, and the schema from `DATABASE_SCHEMA`.
- Running the entry point with the arguments in `migrateArgs` creates or verifies the schema, granting access to the
  role in `DATABASE_ROLE`, and exits.

Other environment variables are set with `environment`. Use one stack per application and environment, and pick a
`name` for each, as names of Azure SQL servers and function apps must be globally unique. Pulumi adds a random suffix.

A Pulumi program of your own can also use the `FunctionsApp` component directly, depending on the `deploy` module.

## Configuration

| Key | Default | Description |
|---|---|---|
| `image` | | Local container image of the application, built for `linux/amd64` |
| `name` | Stack name | Name of the application, used to name its resources. Lower case letters, digits and hyphens. |
| `migrateArgs` | `["migrate"]` | Arguments to the image's entry point which run the migrate admin process |
| `environment` | `{}` | Additional environment variables for the application and the migrate admin process |
| `databaseRole` | `App` | Role given access to the schema by the migrate admin process, and to the application's user |
| `databaseSchema` | | Schema holding the state machine's tables. Defaults to the application's default. |
| `databaseCollation` | `Norwegian_100_CS_AS_SC_UTF8` | Collation of the database |
| `databaseMinCapacity` | `0.5` | Minimum number of vCores of the database while it is not paused |
| `databaseMaxCapacity` | `1` | Maximum number of vCores of the database |
| `databaseAutoPauseDelay` | `60` | Minutes without connections before the database pauses, at least 15, or `-1` to never pause |
| `databaseUseFreeLimit` | `false` | Whether to use the [free Azure SQL Database offer](https://learn.microsoft.com/azure/azure-sql/database/free-offer), of which a subscription has one. The database pauses for the rest of the month when the free limit is used up. |
| `instanceMemory` | `512` | Memory in MB of each instance of the function app: 512, 2048 or 4096 |
| `maximumInstanceCount` | `10` | Maximum number of instances the function app scales out to |
| `deployerIp` | Looked up | Public IP address of the deploying machine, let through the database firewall |

Structured values are set with `--path`, for example `pulumi config set --path 'environment.DATABASE_POOL_SIZE' 5`.

## Limitations

- **Background work stops when the app scales to zero.** The state machine processes delayed triggers and forwards
  outgoing requests in the background, which only happens while an instance of the function app is running. With no
  incoming requests, timeouts and retries are delayed until the next request starts an instance. Configure
  [always ready instances](https://learn.microsoft.com/azure/azure-functions/flex-consumption-how-to#set-always-ready-instance-counts)
  if this matters.
- **Resuming a paused database is slow.** The first connection to a paused database waits until it has resumed, which
  can take a minute, while the Functions host expects the application to start within 30 to 60 seconds. The first
  requests after a pause may therefore fail. Set `databaseAutoPauseDelay` to `-1` to keep the database running.
- **SQL authentication.** The application logs in with a password, as the state machine supports no other way. With
  Microsoft Entra authentication, the function app's managed identity could log in without one, and the server could
  allow Entra authentication only.
- **The database is public.** It is reachable from all of Azure and from the deploying machine, protected by its
  firewall and credentials. Virtual network integration of the function app and a private endpoint for the database
  would avoid that, at a cost.
- **The deploying machine needs access.** Provisioning and migration run on the deploying machine, which must reach
  the database on port 1433.
