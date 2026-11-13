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
- The resource providers of the deployed resources registered in the subscription. A new subscription has none of them,
  and registering them is free:

  ```shell
  for namespace in Microsoft.Sql Microsoft.Web Microsoft.Storage Microsoft.ManagedIdentity \
      Microsoft.OperationalInsights Microsoft.Insights; do
    az provider register --namespace "$namespace" --wait
  done
  ```

## Regions

The region is set with `azure-native:location`, and must support both of these:

- **Flex Consumption with the `custom` runtime.** `az functionapp list-flexconsumption-locations` lists the regions
  with Flex Consumption, and `az functionapp list-flexconsumption-runtimes --location <region> --runtime custom` shows
  whether one has the runtime. See also
  [the regions supporting Flex Consumption](https://learn.microsoft.com/azure/azure-functions/flex-consumption-how-to#view-currently-supported-regions).
- **Creating Azure SQL servers, for your subscription.** Azure restricts this per subscription and region, and new
  subscriptions are restricted in many regions, popular ones in particular. The deployment then fails when creating
  the server, with `ProvisioningDisabled` and "Provisioning is restricted in this region". Check a region before
  deploying:

  ```shell
  az rest --method get --url "https://management.azure.com/subscriptions/$(az account show --query id --output tsv)/providers/Microsoft.Sql/locations/<region>/capabilities?api-version=2021-11-01" \
    --query "[status, reason]" --output tsv
  ```

  `Available` means servers can be created. `Visible`, with a reason, means they can't, unless a support request of
  the type "Service and subscription limits" lifts the restriction.

As an example, a new subscription checked on 2026-11-13 could create servers in `swedencentral`, `germanywestcentral`
and `francecentral`, but not in `northeurope`, `westeurope`, `norwayeast`, `uksouth` or `eastus2`. All of these have
Flex Consumption. The restrictions differ between subscriptions and change over time, so check your own.

The deployment has been tested in `swedencentral`.

## Deploying the hello world demo

Build the image for the platform Azure Functions runs on:

```shell
docker build --platform linux/amd64 -f apps/hello-world/Dockerfile -t tits-hello-world .
```

On an arm64 machine, such as a Mac with Apple Silicon, the build is emulated, and the native image takes long to
build: around 50 minutes with QEMU emulation, even with `--build-arg NATIVE_IMAGE_OPTS=-Ob`, which builds a less
optimized image faster. Emulating with Rosetta instead, if your Docker supports it, e.g. in the virtual machine
settings of Docker Desktop or Rancher Desktop, should be considerably faster. Building on an amd64 machine avoids
emulation altogether.

Build the Pulumi program:

```shell
mvn package -pl deploy -am -DskipTests
```

Then, from the `deploy` directory, create a stack, configure it, and deploy:

```shell
pulumi stack init hello-world
pulumi config set azure-native:location swedencentral
pulumi config set image tits-hello-world
pulumi up
```

Choose a region as described in [Regions](#regions). When done, `pulumi stack output url` is the address of the demo.
`pulumi destroy` deletes everything again.

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
pulumi config set azure-native:location swedencentral
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

## Microsoft Entra authentication

Nothing logs in to the database with a password. The server allows Microsoft Entra authentication only, and every
login presents an Entra access token for Azure SQL, whose resource is `https://database.windows.net/`.

### The server administrator

The administrator of the server is the principal the Azure CLI is logged in as when deploying: a user, or a service
principal, e.g. in CI. Its name and type come from `az account show`, and its object id and tenant from the login
Pulumi uses, which is the same. Provisioning and migration log in as the administrator with a token from
`az account get-access-token`, which is valid for about an hour and is not renewed. That is enough for these short
steps, as each step gets a token of its own.

### The application's database user

The application logs in as a user-assigned managed identity, which the deployment creates along with the function app.
Its database user is created by the provisioning step with

```sql
CREATE USER [app] WITH SID = <the client id of the identity>, TYPE = E
```

rather than `CREATE USER ... FROM EXTERNAL PROVIDER`. That would have the server look the identity up in Microsoft
Graph, which requires giving the server's own identity permissions in Entra ID, such as the Directory Readers role.
With the client id as the SID, nothing is looked up. This is why the identity is user-assigned: the client id of the
function app's system-assigned identity is not known to the deployment. If the identity is replaced, and with it its
client id, the provisioning step creates the user again.

### Tokens in the application

The application gets the tokens of its managed identity itself, in `ManagedIdentityTokens` of `database-mssql`. When
`DATABASE_AUTHENTICATION` is `ActiveDirectoryManagedIdentity`, the data source gets an access token callback of the
SQL Server driver, which the driver asks for a token whenever it opens a connection. The callback fetches the tokens

- from the endpoint Azure Functions and App Service give the application in `IDENTITY_ENDPOINT`, with the secret in
  `IDENTITY_HEADER`, or else
- from the Instance Metadata Service at `169.254.169.254`, which virtual machines and some container services have.

`DATABASE_USER` selects the user-assigned identity by its client id, or is unset for the system-assigned one. A token is
reused until five minutes before it expires, so most connections get one without a request.

### Why not the Azure SDK

The SQL Server driver can fetch the tokens itself, with `azure-identity` of the Azure SDK, which is the usual way. The
apps don't use it, as they are native images:

- `azure-identity` brings in MSAL, `azure-core`, Jackson 2 and more, where all that is needed is one HTTP request.
- It builds into a native image only when `org.slf4j` and `com.fasterxml.jackson` are also initialized at build time,
  as `azure-core` initializes classes at build time which log and parse JSON. Whether it then fetches tokens in a
  native image has not been tried. Every update of the Azure SDK would have to be tried in a native image again.
- The token source is independent of the driver's Entra support, so other token sources can be added beside it.

`ActiveDirectoryServicePrincipal` is the one mode which still uses `azure-identity`, on the JVM only, with the Maven
profile `entra-id` of the identity provider. The `deploy` module itself uses Pulumi's Azure Native provider and the
Azure CLI, not the Azure SDK, and runs on the JVM on the deploying machine only.

### Other cloud providers

Managed identities are Azure's. AWS and GCP have identities of their own, and managed PostgreSQL which their
identities log in to: Amazon RDS and Aurora, and Google Cloud SQL. Their tokens, too, are short-lived and sent as the
password, so they fit the same approach: a small token source for each cloud, fetching tokens from the cloud's own
credentials endpoint without its SDK, supplying the password for each new connection. That is
[#186](https://github.com/the-backend-project/tits/issues/186). Azure Database for PostgreSQL takes Entra tokens, and
could use `ManagedIdentityTokens` for another resource. The `deploy` module only deploys to Azure Functions.

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

## Troubleshooting

What the application writes to stdout, and what the Functions host logs, ends up in Application Insights within a few
minutes. Query it with the Azure CLI, which installs its `application-insights` extension when first used:

```shell
az monitor app-insights query \
  --app "$(az monitor app-insights component show --resource-group "$(pulumi stack output resourceGroupName)" --query "[0].appId" --output tsv)" \
  --analytics-query "union traces, exceptions, requests | where timestamp > ago(30m) | project timestamp, itemType, message, outerMessage, resultCode | order by timestamp asc" \
  --output table
```

- The application's own startup lines, such as `Listening on port`, show that the host started it. The host logs its
  custom handler settings as `HttpWorkerOptions`, where `EnableProxyingHttpRequest` must be `true`.
- `Exception while executing function: Functions.http-handler1` comes with the inner exception in the `details` of
  the exception, e.g. the status the application answered the host with.
- Failed logins to the database appear in the application's lines as exceptions of the SQL Server driver.

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
  firewall and Entra authentication. Virtual network integration of the function app and a private endpoint for the
  database would avoid that, at a cost.
- **The deploying machine needs access.** Provisioning and migration run on the deploying machine, which must reach
  the database on port 1433.
- **Stacks deployed with SQL authentication may lose their database.** Earlier versions of the deployment created the
  server with a SQL administrator and password. Deploying such a stack again switches the server to Entra
  authentication only, which may make Pulumi replace the server, and with it the database. This has not been tried.
  Run `pulumi preview` first, and look for replacements of the server and the database.
