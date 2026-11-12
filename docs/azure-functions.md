# Deploying to Azure Functions

The `deploy` module is a [Pulumi](https://www.pulumi.com) program written in Java, which deploys the
[apps](apps.md), or any other application built the same way, to
[Azure Functions](https://learn.microsoft.com/azure/azure-functions/) on the Flex Consumption plan with a serverless
Azure SQL database. Both scale to zero: you pay for the function app only while it handles requests, and for the
database's compute only while it has connections.

## What is deployed

All resources are created in a resource group of their own:

- An Azure SQL server with a serverless database, and firewall rules letting through Azure services and the deploying
  machine. The server allows [Microsoft Entra authentication](https://learn.microsoft.com/azure/azure-sql/database/authentication-aad-overview)
  only, so there are no passwords. Its administrator is the user or service principal the Azure CLI is logged in as.
- A function app on the Flex Consumption plan, with a storage account for the Functions host and the deployment
  package, which the app accesses with its system-assigned managed identity.
- A user-assigned managed identity, which the application logs in to the database as.
- Application Insights with a Log Analytics workspace, which collects what the application writes to stdout.

Deploying then runs three steps on the deploying machine:

1. Provisions the database: a contained database user for the application's managed identity, and the role which the
   migrate admin process grants access to the schema. This is the counterpart of the `database/init.sql` of the apps
   for local runs.
2. Runs the migrate admin process from the application's image, as the server administrator.
3. Deploys the application to the function app.

The first two steps log in to the database with an access token of the server administrator, from
`az account get-access-token`.

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
  where you may create role assignments, for example as Owner. Pulumi uses the same login. The user or service
  principal logged in becomes the Microsoft Entra administrator of the database server, so deploy the stack again with
  the same login, or the administrator changes to the new one.

## Deploying the hello world demo

Build the image for the platform Azure Functions runs on, which on an arm64 machine is emulated and takes a while:

```shell
docker build --platform linux/amd64 -f apps/hello-world/Dockerfile -t tits-hello-world .
```

Build the Pulumi program:

```shell
mvn package -pl deploy -am -DskipTests
```

Then, from the `deploy` directory, create a stack, configure it, and deploy:

```shell
pulumi stack init hello-world
pulumi config set azure-native:location northeurope
pulumi config set image tits-hello-world
pulumi up
```

Choose a [region supporting Flex Consumption](https://learn.microsoft.com/azure/azure-functions/flex-consumption-how-to#view-currently-supported-regions).
When done, `pulumi stack output url` is the address of the demo. `pulumi destroy` deletes everything again.

## Deploying the identity provider

The [identity provider](identity-provider.md) is deployed the same way, with its issuer set to the URL of the function
app, and its signing keys and bootstrap client kept as secrets in the stack configuration. Build its image and the
Pulumi program:

```shell
docker build --platform linux/amd64 -f apps/identity-provider/Dockerfile -t tits-identity-provider .
mvn package -pl deploy -am -DskipTests
```

Then, from the `deploy` directory:

```shell
pulumi stack init identity-provider
pulumi config set azure-native:location northeurope
pulumi config set image tits-identity-provider
pulumi config set --path 'urlEnvironment[0]' ISSUER
docker run --rm --platform linux/amd64 tits-identity-provider generate-key | tr -d '\n' \
  | pulumi config set --secret --path secretEnvironment.SIGNING_KEYS
pulumi config set --secret --path secretEnvironment.BOOTSTRAP_CLIENT_ID admin
openssl rand -hex 32 | tr -d '\n' | pulumi config set --secret --path secretEnvironment.BOOTSTRAP_CLIENT_SECRET
pulumi up
```

and get a token for the bootstrap client:

```shell
curl -u "admin:$(pulumi config get --path secretEnvironment.BOOTSTRAP_CLIENT_SECRET)" \
  -d grant_type=client_credentials "$(pulumi stack output url)token"
```

Other settings of the identity provider, such as `TOKEN_AUDIENCE`, are set in `environment`, and clients to import in
`CLIENTS`, as the function app has no file for `CLIENTS_FILE`. The bootstrap client id is not secret, but is in
`secretEnvironment` along with its secret, as the identity provider requires both or none, also when it migrates. To rotate the signing keys, set `SIGNING_KEYS` again and
run `pulumi up`, which restarts the identity provider with the new keys.

## Deploying another application

Another application is deployed the same way, as long as it follows the conventions of the apps:

- It is a container image for `linux/amd64` with an entry point which serves HTTP on `$PORT`, built from `scratch` or
  otherwise holding only what the executable needs. The executable is linked statically, or dynamically against a C
  library in the image.
- It reads the location of its database from `DATABASE_HOST` and `DATABASE_NAME`, and the schema from
  `DATABASE_SCHEMA`. It logs in as the managed identity with the client id in `DATABASE_USER` when
  `DATABASE_AUTHENTICATION` is `ActiveDirectoryManagedIdentity`, and with the access token in `DATABASE_ACCESS_TOKEN`
  when it is `ActiveDirectoryAccessToken`, see the [configuration of the demo](hello-world.md#configuration).
- Running the entry point with the arguments in `migrateArgs` creates or verifies the schema, granting access to the
  role in `DATABASE_ROLE`, and exits.

Other environment variables are set with `environment`, secret ones with `secretEnvironment`, and those which are to
hold the URL of the application with `urlEnvironment`. Use one stack per application and environment, and pick a
`name` for each, as names of Azure SQL servers and function apps must be globally unique. Pulumi adds a random suffix.

A Pulumi program of your own can also use the `FunctionsApp` component directly, depending on the `deploy` module.

## Configuration

| Key | Default | Description |
|---|---|---|
| `image` | | Local container image of the application, built for `linux/amd64` |
| `name` | Stack name | Name of the application, used to name its resources. Lower case letters, digits and hyphens. |
| `migrateArgs` | `["migrate"]` | Arguments to the image's entry point which run the migrate admin process |
| `environment` | `{}` | Additional environment variables for the application and the migrate admin process |
| `secretEnvironment` | `{}` | Additional environment variables for the application only, set with `--secret`. They are encrypted in the stack configuration and state, and are app settings of the function app. |
| `urlEnvironment` | `[]` | Environment variables for the application and the migrate admin process set to the URL of the application, `https://` and its host name without a trailing slash |
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
- **The database administrator is a single principal.** The server's Entra administrator is whoever deploys. To let
  others administer the database, a group would be the administrator instead, but that is not configurable.
- **Secrets are app settings.** The values of `secretEnvironment` are app settings of the function app, readable by
  anyone allowed to read its configuration. Key Vault references would keep them in a key vault instead.
- **The URL is that of the function app.** The function app is named by the deployment, with a random suffix, so that
  its URL is known when its settings are. Custom domains are not set up, and `urlEnvironment` does not follow them.
- **The database is public.** It is reachable from all of Azure and from the deploying machine, protected by its
  firewall and credentials. Virtual network integration of the function app and a private endpoint for the database
  would avoid that, at a cost.
- **The deploying machine needs access.** Provisioning and migration run on the deploying machine, which must reach
  the database on port 1433.
