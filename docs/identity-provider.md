# Identity provider

The `apps/identity-provider` module is an OAuth 2.0 authorization server for the
[client credentials grant](https://www.rfc-editor.org/rfc/rfc6749#section-4.4), built with the HTTP inbox and the
state machine. Clients get access tokens to call other services with, and the services verify the tokens with the
identity provider's public keys.

## Endpoints

| Endpoint | Description |
|---|---|
| `POST /token` | The [token endpoint](https://www.rfc-editor.org/rfc/rfc6749#section-3.2). Takes `grant_type=client_credentials` and an optional `scope`. Without a scope the token gets all the scopes of the client. |
| `POST /clients` | Registers a client, e.g. `{"client_id": "orders", "scope": "orders:read orders:write"}`. Requires a bearer token with the scope `client-registration`. Responds with the client's generated `client_secret`, which is shown only this once. |
| `GET /.well-known/jwks.json` | The public keys the tokens are signed with. |
| `GET /.well-known/oauth-authorization-server` | The [authorization server metadata](https://www.rfc-editor.org/rfc/rfc8414). |
| `GET /health` | Responds 200 while the process is serving. |

Clients authenticate to the token endpoint with HTTP Basic, which every OAuth client supports. A secret in the request
body is refused (see [Credentials](#credentials)). Errors are the error responses of
[RFC 6749 section 5.2](https://www.rfc-editor.org/rfc/rfc6749#section-5.2), with `invalid_client` responded with 401.
An unknown client and a wrong secret get the same response, so the token endpoint does not tell which clients exist.

Access tokens are JWTs as profiled by [RFC 9068](https://www.rfc-editor.org/rfc/rfc9068): of type `at+jwt`, signed with
RS256, and with the claims `iss`, `sub` and `client_id` (both the client id), `scope`, `jti`, `iat`, `exp`, and `aud`
if configured. Core's `AccessTokenSupplier` gets tokens from the identity provider as from any other.

## Model

| Entity | Description |
|---|---|
| `Client` | A registered client, with the hash of its secret, its scopes and who registered it. Found by its client id, which is an id event of `ClientIds` and therefore unique. |
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
- The bearer token of a registration is verified by the edge, which passes on the client id of its subject. The edge
  generates the secret of the new client, passes on its hash, and adds the secret to the response on its way out.

The edge tells the state machine what it found in `X-Idp-` headers, which it removes from incoming requests. The event
log therefore holds the hashes of secrets and the claims of tokens, but never a secret or a token. Secrets are 256
random bits, so a fast hash (SHA-256) is enough: slow hashes only protect secrets that can be guessed.

## Running

The identity provider is configured by environment variables:

| Variable | Description |
|---|---|
| `ISSUER` | Required. The URL the identity provider is reached at, which is the `iss` of its tokens, e.g. `https://idp.example.com`. |
| `SIGNING_KEYS` | Required. A JWK set with private RSA keys with key ids. The first key signs, and the public keys of all are published. To rotate, add the new key last, wait for the services to have fetched it, then move it first, and remove the old key once its tokens have expired. |
| `TOKEN_LIFETIME_SECONDS` | How long the tokens are valid. Default 300. |
| `TOKEN_AUDIENCE` | The `aud` of the tokens. Default none. |
| `BOOTSTRAP_CLIENT_ID`, `BOOTSTRAP_CLIENT_SECRET` | A client registered at startup with the scope `client-registration`, so the first clients can be registered. The secret must have at least 32 characters. If the client id is taken, e.g. by an earlier start, the registered client is kept as is. |
| `PORT` | Default 8080. |
| `DATABASE` | `mssql` or `pgsql`. Default `mssql`. |
| `DATABASE_HOST`, `DATABASE_PORT`, `DATABASE_NAME`, `DATABASE_USER`, `DATABASE_PASSWORD` | The database. The port defaults to that of the database. |
| `DATABASE_SCHEMA` | Default `identity_provider`. |
| `DATABASE_ROLE` | The role given access to the schema. Required by `migrate`. |
| `DATABASE_TRUST_SERVER_CERTIFICATE`, `DATABASE_POOL_SIZE`, `DATABASE_QUERY_TIMEOUT_SECONDS` | As for the demo. |

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

`IdentityProviderTest` runs the identity provider against the test database.

## Gaps

- Clients can't be changed or removed, and their secrets can't be rotated.
- Tokens can't be revoked, which keeps their lifetime short.
- There is no container image yet.
