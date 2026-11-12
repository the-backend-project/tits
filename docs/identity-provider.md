# Identity provider

The `apps/identity-provider` module is an OAuth 2.0 authorization server for the
[client credentials grant](https://www.rfc-editor.org/rfc/rfc6749#section-4.4), built with the HTTP inbox and the
state machine. Clients get access tokens to call other services with, and the services verify the tokens with the
identity provider's public keys, e.g. with the HTTP inbox's [`BearerTokenFilter`](architecture.md#access-tokens).

## Endpoints

| Endpoint | Description |
|---|---|
| `POST /token` | The [token endpoint](https://www.rfc-editor.org/rfc/rfc6749#section-3.2). Takes `grant_type=client_credentials` and an optional `scope`. Without a scope the token gets all the scopes of the client. |
| `POST /clients` | Registers a client, e.g. `{"client_id": "orders", "scope": "orders:read orders:write"}`, with an optional `subject` (see below). Without a `scope` the client has no scopes. Requires a bearer token with the scope `client-registration`. Responds with the client's generated `client_secret`, which is shown only this once, and its `subject` if it has one. |
| `GET /.well-known/jwks.json` | The public keys the tokens are signed with. |
| `GET /.well-known/oauth-authorization-server` | The [authorization server metadata](https://www.rfc-editor.org/rfc/rfc8414). |
| `GET /health` | Responds 200 while the process is serving. |

Clients authenticate to the token endpoint with HTTP Basic, which every OAuth client supports. A secret in the request
body is refused (see [Credentials](#credentials)). Errors are the error responses of
[RFC 6749 section 5.2](https://www.rfc-editor.org/rfc/rfc6749#section-5.2), with `invalid_client` responded with 401.
An unknown client and a wrong secret get the same response, so the token endpoint does not tell which clients exist.

Access tokens are JWTs as profiled by [RFC 9068](https://www.rfc-editor.org/rfc/rfc9068): of type `at+jwt`, signed with
RS256, and with the claims `iss`, `sub`, `client_id`, `scope`, `jti`, `iat`, `exp`, and `aud` if configured. Core's
`AccessTokenSupplier` gets tokens from the identity provider as from any other.

The `sub` of a token is the subject of its client, or the client id if the client has no subject. The `client_id` is
always the client id. A subject lets a client be known to a service by a value a client id can't hold, e.g.
`https://wallet.example.com/provider/example` or `merchantaggregator:6f1c2a9e-3b7d-4e58-9a0c-2d4b8e1f7a63`: any text of 1
to 255 characters, not blank and without control characters. Subjects are not required to be unique. A registration
with an invalid subject is refused with 400, like any other invalid registration:

```json
{"client_id": "example-wallet", "scope": "payments", "subject": "https://wallet.example.com/provider/example"}
```

```json
{
  "client_id": "example-wallet",
  "subject": "https://wallet.example.com/provider/example",
  "client_id_issued_at": 1794484429,
  "scope": "payments",
  "grant_types": ["client_credentials"],
  "token_endpoint_auth_method": "client_secret_basic",
  "client_secret": "...",
  "client_secret_expires_at": 0
}
```

The registration endpoint identifies the client of a bearer token by its `client_id` claim, so a client registers
other clients as its client id whatever its subject. The bootstrap client has no subject.

## Model

| Entity | Description |
|---|---|
| `Client` | A registered client, with its subject if it has one, the hash of its secret, its scopes and the client id of the client which registered it. Found by its client id, which is an id event of `ClientIds` and therefore unique. |
| `Client registration` | A registration request: accepted, creating a client, or rejected because the client id is taken. |
| `Token request` | A token request: the token issued, or why it was refused. Its entity id is the `jti` of the token. |

Every registration and token request is an entity of its own, so the event log is a complete record of who registered
which clients and which tokens were issued to whom, without the event log of a client growing with its tokens. A token
request reads its client with a read-only event.

## Credentials

The HTTP inbox stores the requests and responses in the event log. Credentials are kept out of it by a filter of the
listener, the edge, which is the only place credentials are handled:

- The token endpoint's Basic credentials are replaced by the client id and the hash of the secret before the request
  enters the state machine, which compares the hash with that of the client.
- The state machine issues the claims of a token, which the edge signs on their way out. The signing keys are never
  seen by the state machine.
- The bearer token of a registration is verified by the edge, which passes on its client id. The edge
  generates the secret of the new client, passes on its hash, and adds the secret to the response on its way out.

The edge tells the state machine what it found in `X-Idp-` headers, which it removes from incoming requests. The event
log therefore holds the hashes of secrets and the claims of tokens, but never a secret or a token. Secrets are 256
random bits, so a fast hash (SHA-256) is enough: slow hashes only protect secrets that can be guessed.

### Imported clients

Clients moved from another identity provider keep their secrets, which may be guessable and are known here only by
their BCrypt hashes, e.g. of Spring Security's `BCryptPasswordEncoder`. The clients are declared in the configuration
(see `CLIENTS_FILE` below), and are registered at startup unless their client ids are taken. The edge keeps their BCrypt
hashes in memory, and verifies the secret of an imported client against its BCrypt hash:

- A matching secret is passed on as the SHA-256 hash of the BCrypt hash, which is what the client is registered with.
- A secret which does not match is passed on as a hash that matches no client, also when the secret is the BCrypt hash
  itself.

The state machine thus compares hashes as for any other client, and neither the secrets nor their BCrypt hashes enter
the event log. The responses to an unknown client and a wrong secret are the same, but a wrong secret of an imported
client takes the time of BCrypt to refuse, which tells that the client id is that of an imported client. Imported
clients are registered by `(import)`, which is no client id.

## Running

Requires Docker (or Podman) with Compose. From the `apps/identity-provider` directory:

```shell
docker compose build migrate
export SIGNING_KEYS=$(docker run --rm tits-identity-provider generate-key)
docker compose up
```

Compose starts a SQL Server database, provisions a database and login for the identity provider, runs the schema
migration as a one-off process, and then starts the identity provider on port 8080 with a bootstrap client:

```shell
curl -u admin:A_local_bootstrap_secret_of_32_characters -d grant_type=client_credentials localhost:8080/token
```

Like the [demo](hello-world.md), the image holds a native executable built with GraalVM, in an image built from
`scratch`. It can also be built alone, from the repository root:

```shell
docker build -f apps/identity-provider/Dockerfile -t tits-identity-provider .
```

To run the identity provider in Azure, see [Deploying to Azure Functions](azure-functions.md#deploying-the-identity-provider).

The identity provider's own reachability metadata registers its records, which Jackson reads the components of, and
the constructors the JSON parser of Nimbus JOSE + JWT creates collections with. Check it as described for the
[demo](hello-world.md#reachability-metadata).

### Configuration

The identity provider is configured by environment variables:

| Variable | Description |
|---|---|
| `ISSUER` | Required to serve. The URL the identity provider is reached at, which is the `iss` of its tokens, e.g. `https://idp.example.com`. |
| `SIGNING_KEYS` | Required to serve. A JWK set with private RSA keys with key ids. The first key signs, and the public keys of all are published. To rotate, add the new key last, wait for the services to have fetched it, then move it first, and remove the old key once its tokens have expired. |
| `TOKEN_LIFETIME_SECONDS` | How long the tokens are valid. Default 300. |
| `TOKEN_AUDIENCE` | The `aud` of the tokens. Default none. |
| `BOOTSTRAP_CLIENT_ID`, `BOOTSTRAP_CLIENT_SECRET` | A client registered at startup with the scope `client-registration`, so the first clients can be registered. The secret must have at least 32 characters. If the client id is taken, e.g. by an earlier start, the registered client is kept as is. |
| `CLIENTS_FILE` | The path of a JSON file with clients to import at startup, see [Imported clients](#imported-clients) and below. |
| `CLIENTS` | The JSON of `CLIENTS_FILE` itself, for when that is easier to provide. At most one of the two may be set. |
| `PORT` | Default 8080. |
| `DATABASE` | `mssql` or `pgsql`. Default `mssql`. |
| `DATABASE_HOST`, `DATABASE_PORT`, `DATABASE_NAME`, `DATABASE_USER`, `DATABASE_PASSWORD` | The database. The port defaults to that of the database. The user and password depend on `DATABASE_AUTHENTICATION`. |
| `DATABASE_AUTHENTICATION` | How to authenticate to the database. `SqlPassword` (the default) with the user and password of a login. For Azure SQL with Microsoft Entra ID: `ActiveDirectoryServicePrincipal` with the client id of the service principal as `DATABASE_USER` and its client secret as `DATABASE_PASSWORD`, or `ActiveDirectoryManagedIdentity` with the managed identity of the host, without `DATABASE_PASSWORD`, and with the client id of a user-assigned identity as `DATABASE_USER` (unset for the system-assigned identity). Entra ID is for `mssql` only, and on the JVM only: the driver gets its tokens with `com.azure:azure-identity`, which is not in the native image. Add it and `com.azure:azure-core-http-jdk-httpclient` to the classpath, at the versions managed by the parent POM, or build with the Maven profile `entra-id`. |
| `DATABASE_SCHEMA` | Default `identity_provider`. |
| `DATABASE_ROLE` | The role given access to the schema. Required by `migrate`. |
| `DATABASE_TRUST_SERVER_CERTIFICATE`, `DATABASE_POOL_SIZE`, `DATABASE_QUERY_TIMEOUT_SECONDS` | As for the [hello world demo](hello-world.md#configuration). |

Like the demo, the process serves HTTP by default, and has admin commands to run as one-off processes:

```shell
identity-provider generate-key   # prints a JWK set with a new key, for SIGNING_KEYS
identity-provider migrate        # creates the schema, with credentials allowed to
identity-provider                # serves
```

To register a client, get a token for the bootstrap client and post the registration:

```shell
token=$(curl -s -u "$BOOTSTRAP_CLIENT_ID:$BOOTSTRAP_CLIENT_SECRET" -d grant_type=client_credentials \
  https://idp.example.com/token | jq -r .access_token)
curl -s -H "Authorization: Bearer $token" -H 'Content-Type: application/json' \
  -d '{"client_id": "orders", "scope": "orders:read orders:write"}' https://idp.example.com/clients
```

The clients to import are a JSON array:

```json
[
  {
    "client_id": "example-wallet",
    "secret_hash": "$2a$10$Uekj8xXissq6Wy3fUSUOW.epdfkVAf3.uY6/DkL8L43gfnvmTrB82",
    "subject": "https://wallet.example.com/provider/example",
    "scope": "payments:read payments:write",
    "client_name": "Example wallet"
  },
  {"client_id": "aggregator", "secret_hash": "$2b$12$..."}
]
```

| Field | Description |
|---|---|
| `client_id` | Required. A client id as for a registration. |
| `secret_hash` | Required. The BCrypt hash of the secret, with the prefix `$2a$`, `$2b$` or `$2y$` and any cost. |
| `subject` | Optional. The `sub` of the client's tokens, as for a registration. |
| `scope` | Optional. The space separated scopes of the client. Absent or empty for none. |
| `client_name` | Optional. Only logged. |

Startup fails if the JSON is invalid, a field is unknown or invalid, a client id is repeated, or a client id is that of
the bootstrap client. A client whose client id is taken, e.g. by an earlier start, is kept as is and logged: changing
the subject, scope or secret hash of an imported client has no effect once it is registered, and a changed secret hash
locks the client out, as the edge verifies secrets against the hash in the configuration. Likewise, a client id taken by
a client registered through `POST /clients` must not be imported, as the edge would no longer accept its secret.

A process which resolves the configuration itself, e.g. the signing keys from a key vault, can run the identity
provider on the JVM with `IdentityProvider.run(args, env)`, which takes the commands of the executable and is
configured by the map `env` instead of the environment variables. `IdentityProvider.main(args)` runs it with the
environment variables. Like `main`, `run` exits the process if serving fails to start.

`IdentityProviderTest` runs the identity provider against the test database.

## Gaps

- Clients can't be changed or removed, and their secrets can't be rotated. This includes imported clients, whose changes
  in the configuration are ignored once they are registered.
- Tokens can't be revoked, which keeps their lifetime short.
- The image is only run on SQL Server. On PostgreSQL the identity provider is only tested on the JVM.
