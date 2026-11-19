# Identity provider

The `apps/identity-provider` module is an OAuth 2.0 authorization server for the
[client credentials grant](https://www.rfc-editor.org/rfc/rfc6749#section-4.4), built with the HTTP inbox and the
state machine. Clients get access tokens to call other services with, and the services verify the tokens with the
identity provider's public keys, e.g. with the HTTP inbox's [`bearerToken` authorizer](http-inbox.md#access-tokens).

It is also an [OpenID Connect](https://openid.net/specs/openid-connect-core-1_0.html) provider for the
[authorization code flow](https://www.rfc-editor.org/rfc/rfc6749#section-4.1), so web apps can sign users in with it
(see [Signing users in](#signing-users-in)). Users sign in with an upstream OpenID Connect provider, e.g. Microsoft
Entra ID, so they keep their single sign-on and no passwords are kept here.

## Model

| Entity | Description |
|---|---|
| `Client` | A registered client, with its subject if it has one, the hash of its secret, its scopes and the client id of the client which registered it. Found by its client id, which is an id event of `ClientIds` and therefore unique. |
| `Client registration` | A registration request: accepted, creating a client, or rejected because the client id is taken. |
| `Token request` | A token request: the tokens issued, or why they were refused. Its entity id is the `jti` of the access token. The UserInfo endpoint reads the claims of the user from the token request of the access token. |
| `Authorization` | An authorization request of a web app: the user who signed in and the code issued, or why it was refused or failed. Found by the hash of its code, which is an id event of `Codes`. |

Every registration, authorization and token request is an entity of its own, so the event log is a complete record of
who registered which clients, which users signed in to which clients, and which tokens were issued to whom, without the
event log of a client growing with its tokens. A token request reads its client with a read-only event.

A refused token request is an event of [severity](monitoring.md#severity) `Warning`, so attempts to guess a secret
show up in the monitoring. So are an authorization request with an unknown client or redirect URI, and a failed
sign-in with the upstream provider.

The [state diagrams](diagrams.md) are generated from the model. Hover over the name of an event for its details.

`Client`:

<object data="../images/com.github.thxmasj.backend.identityprovider.Client.svg" type="image/svg+xml"></object>

[Open the diagram in full size](images/com.github.thxmasj.backend.identityprovider.Client.svg)

`Client registration`:

<object data="../images/com.github.thxmasj.backend.identityprovider.ClientRegistration.svg" type="image/svg+xml"></object>

[Open the diagram in full size](images/com.github.thxmasj.backend.identityprovider.ClientRegistration.svg)

`Token request`:

<object data="../images/com.github.thxmasj.backend.identityprovider.TokenRequest.svg" type="image/svg+xml"></object>

[Open the diagram in full size](images/com.github.thxmasj.backend.identityprovider.TokenRequest.svg)

`Authorization`:

<object data="../images/com.github.thxmasj.backend.identityprovider.Authorization.svg" type="image/svg+xml"></object>

[Open the diagram in full size](images/com.github.thxmasj.backend.identityprovider.Authorization.svg)

The entity models, with those of the [HTTP inbox](http-inbox.md), and how they relate through triggers:

<object data="../images/com.github.thxmasj.backend.identityprovider.er.svg" type="image/svg+xml"></object>

[Open the diagram in full size](images/com.github.thxmasj.backend.identityprovider.er.svg)

## Endpoints

| Endpoint | Description |
|---|---|
| `POST /token` | The [token endpoint](https://www.rfc-editor.org/rfc/rfc6749#section-3.2). Takes `grant_type=client_credentials` and an optional `scope`. Without a scope the token gets all the scopes of the client. Takes `grant_type=authorization_code` with `code`, `redirect_uri` and `code_verifier` if the authorization request had a PKCE challenge (see [Signing users in](#signing-users-in)). |
| `POST /clients` | Registers a client, e.g. `{"client_id": "orders", "scope": "orders:read orders:write"}`, with an optional `subject`, `grant_types` and `redirect_uris` (see below). Without a `scope` the client has no scopes. Requires a bearer token with the scope `client-registration`. Responds with the client's generated `client_secret`, which is shown only this once, and its `subject` if it has one. |
| `GET /authorize` | The [authorization endpoint](https://openid.net/specs/openid-connect-core-1_0.html#AuthorizationEndpoint), where users sign in to web apps. Only if an upstream provider is configured. |
| `GET /callback` | Where the upstream provider redirects users to after they signed in. Only if an upstream provider is configured. |
| `GET /userinfo` | The [UserInfo endpoint](https://openid.net/specs/openid-connect-core-1_0.html#UserInfo), with the access token of a user as bearer token. Only if an upstream provider is configured. |
| `GET /.well-known/jwks.json` | The public keys the tokens are signed with. |
| `GET /.well-known/oauth-authorization-server` | The [authorization server metadata](https://www.rfc-editor.org/rfc/rfc8414). |
| `GET /.well-known/openid-configuration` | The [OpenID provider metadata](https://openid.net/specs/openid-connect-discovery-1_0.html#ProviderMetadata), the same as the authorization server metadata. Only if an upstream provider is configured. |
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

A client has the grant types of `grant_types`: `client_credentials`, `authorization_code` or both. Without
`grant_types` a client has `client_credentials`. A client of the `authorization_code` grant signs users in, and needs
`redirect_uris`, the URIs the users are redirected back to it at. They are absolute `https` URIs without a fragment, or
`http` URIs of `localhost`, `127.0.0.1` or `[::1]` for development, of at most 2000 characters, and an authorization
request must give one of them exactly. A client may only use its grant types, and is otherwise refused with
`unauthorized_client`:

```json
{"client_id": "webapp", "grant_types": ["authorization_code"], "redirect_uris": ["https://app.example.com/login/oauth2/code/idp"]}
```

```json
{
  "client_id": "webapp",
  "client_id_issued_at": 1794484429,
  "scope": "",
  "grant_types": ["authorization_code"],
  "response_types": ["code"],
  "redirect_uris": ["https://app.example.com/login/oauth2/code/idp"],
  "token_endpoint_auth_method": "client_secret_basic",
  "client_secret": "...",
  "client_secret_expires_at": 0
}
```

## Signing users in

A web app signs users in with the identity provider as with any OpenID Connect provider, configured with the issuer
URL, its client id and secret, and the scopes `openid email profile`. Its OpenID Connect library finds the endpoints
by [discovery](https://openid.net/specs/openid-connect-discovery-1_0.html), e.g. with Spring Security:

```yaml
spring.security.oauth2.client:
  provider.idp.issuer-uri: https://idp.example.com
  registration.idp:
    client-id: webapp
    client-secret: ...
    scope: openid, email, profile
```

The flow is the [authorization code flow](https://openid.net/specs/openid-connect-core-1_0.html#CodeFlowAuth), with
the web app as a confidential client:

1. The web app redirects the user to `GET /authorize` with `response_type=code`, its `client_id` and `redirect_uri`,
   a `scope` with `openid`, and `state` and `nonce`. A PKCE challenge, `code_challenge` with
   `code_challenge_method=S256`, is allowed, and then required at the token endpoint.
2. The identity provider checks the request, and redirects the user to the upstream provider to sign in. A request with
   an unknown client or a redirect URI the client has not registered is answered with an error page, as the web app
   can't be redirected to. Other errors are told the web app at its redirect URI, with `error` and `state`, e.g.
   `invalid_scope` for a scope without `openid`, or `login_required` for `prompt=none`, as the identity provider keeps
   no session to sign users in without asking.
3. The upstream provider redirects the user back to `GET /callback`, where the identity provider redeems the upstream
   provider's code and verifies its ID token. The user is redirected to the web app with a `code` and the `state`, or
   with `access_denied` if the user cancelled.
4. The web app redeems the code at `POST /token` with `grant_type=authorization_code`, authenticated with HTTP Basic as
   for the client credentials grant. A code is valid for 60 seconds, for the client and redirect URI it was issued for,
   and can be redeemed once: any attempt to redeem it uses it up, also one refused for a wrong code verifier. A client
   with a wrong secret is refused before the code is redeemed. Refused codes are `invalid_grant`.
5. The token response has an access token and an ID token. The web app verifies the ID token with the keys of
   `jwks_uri`, and can call `GET /userinfo` with the access token.

Of the scopes requested, the identity provider grants `openid`, `email` and `profile`, and ignores others. The ID
token is a JWT of type `JWT`, signed with RS256, with the claims `iss`, `sub`, `aud` (the client id), `exp`, `iat`,
`auth_time`, `nonce` if the request had one, `email` and `email_verified` with the scope `email`, and `name` with the
scope `profile`. The UserInfo endpoint responds with `sub` and the same `email`, `email_verified` and `name`. The access
token is that of the client credentials grant, with the scopes granted, and is valid at the UserInfo endpoint only.

The claims of the user are those of the upstream provider's ID token: `sub`, `email`, `email_verified` and `name`.
The `sub` of the user is the `sub` at the upstream provider, and `email_verified` is `false` unless the upstream
provider says otherwise. Microsoft Entra ID, for example, gives `email` only as an
[optional claim](https://learn.microsoft.com/entra/identity-platform/optional-claims), and never `email_verified`.

The identity provider is a confidential client of the upstream provider, with the redirect URI `<ISSUER>/callback`
registered there (see `UPSTREAM_ISSUER` below). The upstream provider must support PKCE with S256. The sign-in is bound
to the browser it was started in by a cookie, which holds the secret its PKCE verifier and nonce are derived from, and
which is sent to `/callback` only, for 10 minutes.

## Backoffice

The [backoffice](backoffice.md) of the identity provider, `apps/identity-provider-backoffice`, is an application of
its own, in which users look up the clients, authorizations and token requests, with the inbox requests which made
them. It reads the schema of the identity provider, `identity_provider`, with a role which may only read it, and keeps
its sessions in a schema of its own, `identity_provider_backoffice`. As the identity provider is public, its backoffice
can be deployed where only its users reach it.

It signs its users in with the identity provider, which signs them in with the upstream provider. Import a client for
it with the grant type `authorization_code` and the redirect URI `<backoffice URL>/backoffice/callback` (see
[Imported clients](#imported-clients)), and set `BACKOFFICE_ISSUER` of the backoffice to the issuer,
`BACKOFFICE_CLIENT_ID` and `BACKOFFICE_CLIENT_SECRET` to the client, and `BACKOFFICE_CLAIM` to `email` and
`BACKOFFICE_CLAIM_VALUES` to the email addresses of its users. The ID tokens of the identity provider have the email
address of the user if the scope `email` is granted. The identity provider passes on the address the upstream provider
gives, so permitting users by it relies on the upstream provider verifying the addresses and not letting users choose
them, as with the work accounts of an organisation in Microsoft Entra ID. The identity provider passes on no roles or
groups to permit users by instead (see [Which claim to permit users by](backoffice.md#which-claim-to-permit-users-by)).

## Credentials

The HTTP inbox stores the requests and responses in the event log. Credentials are kept out of it by a filter of the
listener, the edge, which is the only place credentials are handled:

- The token endpoint's Basic credentials are replaced by the client id and the hash of the secret before the request
  enters the state machine, which compares the hash with that of the client.
- The state machine issues the claims of a token, which the edge signs on their way out. The signing keys are never
  seen by the state machine.
- The bearer token of a registration is verified by the edge, which passes on its client id. The edge
  generates the secret of the new client, passes on its hash, and adds the secret to the response on its way out.
- The authorization codes are generated by the edge, which passes on their hashes, and adds the code to the redirect
  on its way out. At the token endpoint the edge replaces the code and the code verifier by their hashes. The SHA-256
  hash of the code verifier is its PKCE challenge, which the state machine compares with that of the request.
- The edge signs users in with the upstream provider, and passes on the claims of the user. The upstream provider's
  code and tokens, the client secret of the identity provider there, and the secret of the cookie never enter the state
  machine. The edge does not pass on cookies at all.
- The edge verifies the access token of the UserInfo endpoint, and passes on its `jti`, which is the entity id of the
  token request with the claims of the user.

The edge tells the state machine what it found in `X-Idp-` headers, which it removes from incoming requests. The event
log therefore holds the hashes of secrets and codes, the claims of tokens and of the users who signed in, but never a
secret, a code or a token. Secrets and codes are 256 random bits, so a fast hash (SHA-256) is enough: slow hashes only
protect secrets that can be guessed.

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

Compose also runs the [backoffice](#backoffice), at http://localhost:8081/backoffice/, with a login and role of its
own and an imported client of its own. Build its image with `docker compose build migrate backoffice-migrate`. Users
sign in to it with an upstream provider, so add `UPSTREAM_ISSUER`, `UPSTREAM_CLIENT_ID` and `UPSTREAM_CLIENT_SECRET`
to the environment of `app`, and set `BACKOFFICE_USERS` to the email addresses of the users, separated by commas.
Without an upstream provider, the backoffice tells that the identity provider can't be reached. The backoffice shares
the network of the identity provider, so the identity provider is at `localhost:8080` for both the browser and the
backoffice, which the issuer must be.

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
| `TOKEN_LIFETIME_SECONDS` | How long the tokens are valid, access tokens and ID tokens. Default 300. |
| `TOKEN_AUDIENCE` | The `aud` of the tokens. Default none. |
| `BOOTSTRAP_CLIENT_ID`, `BOOTSTRAP_CLIENT_SECRET` | A client registered at startup with the scope `client-registration`, so the first clients can be registered. The secret must have at least 32 characters. If the client id is taken, e.g. by an earlier start, the registered client is kept as is. |
| `CLIENTS_FILE` | The path of a JSON file with clients to import at startup, see [Imported clients](#imported-clients) and below. |
| `CLIENTS` | The JSON of `CLIENTS_FILE` itself, for when that is easier to provide. At most one of the two may be set. |
| `UPSTREAM_ISSUER`, `UPSTREAM_CLIENT_ID`, `UPSTREAM_CLIENT_SECRET` | The upstream OpenID Connect provider users sign in with, e.g. `https://login.microsoftonline.com/<tenant>/v2.0`, and the client id and secret of the identity provider there, with the redirect URI `<ISSUER>/callback`. All three or none. The endpoints are found by discovery at startup, which fails if the upstream provider can't be reached. Without them users can't sign in, and the endpoints of [Signing users in](#signing-users-in) are not served. |
| `UPSTREAM_SCOPE` | The scope requested from the upstream provider, with `openid`. Default `openid email profile`. |
| `PORT` | Default 8080. |
| `DATABASE` | `mssql` or `pgsql`. Default `mssql`. |
| `DATABASE_HOST`, `DATABASE_PORT`, `DATABASE_NAME` | The database. The port defaults to that of the database. |
| `DATABASE_AUTHENTICATION`, `DATABASE_USER`, `DATABASE_PASSWORD`, `DATABASE_ACCESS_TOKEN` | How to log in to the database, as for the [hello world demo](hello-world.md#configuration). Microsoft Entra ID is for `mssql` only. For `ActiveDirectoryServicePrincipal`, which works on the JVM only, build with the Maven profile `entra-id`. |
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
| `grant_types` | Optional. The grant types of the client, as for a registration. Default `["client_credentials"]`. |
| `redirect_uris` | Optional. The redirect URIs of the client, as for a registration. Required for `authorization_code`. |
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

`IdentityProviderTest` runs the identity provider against the test database, with users signing in to a web app with
the OpenID Connect client of the [Nimbus OAuth 2.0 SDK](https://connect2id.com/products/nimbus-oauth-openid-connect-sdk)
and a stub upstream provider.

## Gaps

- Clients can't be changed or removed, and their secrets can't be rotated. This includes imported clients, whose changes
  in the configuration are ignored once they are registered.
- Tokens can't be revoked, which keeps their lifetime short. A code redeemed twice does not revoke the tokens issued
  for it, as [RFC 6749 section 4.1.2](https://www.rfc-editor.org/rfc/rfc6749#section-4.1.2) suggests.
- Signing users in has no refresh tokens, no session (so `prompt=none` always fails with `login_required`), and no
  logout. Clients are confidential: there are no public clients, which would need PKCE and no secret.
- The claims of a user are only those the upstream provider gave when the user signed in. The `sub` is the upstream
  provider's, so the users get new subjects if the upstream provider is changed.
- The image is only run on SQL Server. On PostgreSQL the identity provider is only tested on the JVM.
