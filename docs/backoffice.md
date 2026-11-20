# Backoffice

The `backoffice` module is a web application in which users look up the entities of an application and follow the
graph of an entity: its event log, its ids and index keys, the events it waits for, the entities its events refer to,
and the entities referring to it, such as its [inbox](http-inbox.md) and [outbox](http-outbox.md) requests. Users sign
in with [OpenID Connect](https://openid.net/specs/openid-connect-core-1_0.html), and only those whose ID token has a
permitted claim value get in. The backoffice only reads.

The backoffice of an application is an application of its own, packaged and deployed apart from the application it
looks into, with the application's entity models, which it needs to unmarshal the data of the events. It is a state
machine of its own, in a schema of its own: every request it receives is an inbox request, and every decision a
transition, from signing users in with the provider to rendering the pages. Every sign-in is an entity whose event log
tells who signed in and which pages they viewed. It reads the application's schema with a role which may only read it. The pages are HTML, made interactive with
[htmx](https://htmx.org), which the backoffice serves itself.

Both [apps](apps.md) have one, `apps/identity-provider-backoffice` and `apps/hello-world-backoffice`. The backoffice of
the [identity provider](identity-provider.md#backoffice) signs its users in with the identity provider.

Kept apart, the backoffice doesn't add to the footprint or the attack surface of the application. It can be deployed
where only its users reach it, e.g. on a private network, while the application is public, and a bug in it can't
change the application's data. Its sessions and the load of its users stay out of the application's schema.

## Model

| Entity | Description |
|---|---|
| `BackofficeSession` | A sign-in of a user: the sign-in started, the code redeemed, who signed in, or was refused, every page viewed, and the sign-out or expiry. Found by the hash of the token of the session cookie, which is an id event of `BackofficeTokens`. |
| `BackofficeTokenRequest` | The request redeeming the code of a sign-in at the provider, an at-most-once [outbox](http-outbox.md) request, as a code can be redeemed only once |
| `BackofficeResource` | A request not of a session: a static file, the health check, a redirect to the start page, or a page not found |

<object data="../images/com.github.thxmasj.backend.backoffice.BackofficeSession.svg" type="image/svg+xml"></object>

[Open the diagram in full size](images/com.github.thxmasj.backend.backoffice.BackofficeSession.svg)

A refused sign-in is an event of [severity](monitoring.md#severity) `Warning`, so attempts to get in show up in the
monitoring. A session expires after its lifetime, or after 30,000 events, as the event numbers of an entity are
`SMALLINT`.

## The backoffice of an application

An application's backoffice is a module of its own, which depends on the application for its entity models and on
`backoffice`, and whose main method runs `BackofficeApp` with the name of the application, its default schema and the
entity models of its state machine:

```java
public static void main(String[] args) {
  BackofficeApp.run("Orders", "orders", Orders.Inbox.entityModels(), args, System.getenv());
}
```

The application exports the package of its entity models to the module of its backoffice, e.g.
`exports com.example.orders to com.example.orders.backoffice;`. The backoffice is built and deployed like the
[apps](apps.md): a native executable in an image built from `scratch`, with a migrate admin process. It is named after
the application, e.g. "Orders backoffice", in the header and the title of its pages.

`Backoffice` gives the model of the backoffice's state machine, which `BackofficeApp` runs with no filter in front of
its inbox. The inbox requests read the cookies, and the transitions decide everything: whether a session is valid,
whether the provider signed the user in and the claims permit the user, and what the response is, the pages included,
which they render from the application's schema. Calling the provider is the only work outside the transitions: they
find its endpoints and check ID tokens against its keys through a helper, and an outbox sends the token request. The
backoffice serves the pages under `/backoffice`, its static files and `/health`.

As every request and response is stored, the event log of the backoffice holds the cookies, the secret of each sign-in,
the codes and token responses of the provider and the pages, and every request creates entities, also requests of
someone who hasn't signed in. Keeping such data out of the event log is
[#285](https://github.com/the-backend-project/backend/issues/285). Until then, give the backoffice's schema the
protection of its credentials, and keep the backoffice where only its users reach it.

## The database

The backoffice uses the database of the application, with two schemas:

| Schema | Default | The backoffice's role may |
|---|---|---|
| The backoffice's own, `DATABASE_SCHEMA` | The application's with `_backoffice` added, e.g. `orders_backoffice` | Read and write it, as a state machine its own schema |
| The application's, `APPLICATION_SCHEMA` | The application's default, e.g. `orders` | Only read it |

Its migrate admin process, run as an administrator after the application's, creates its schema for its role,
`DATABASE_ROLE`, as the application's creates the application's, and grants the role read access to the application's
schema with `SqlDatabase.grantRead`: `SELECT` on the schema on SQL Server, and `USAGE` on the schema and `SELECT` on its
tables on PostgreSQL. The backoffice logs in with a login of its own, a member of its role, so the application's login
and role are not shared with it.

## Pages

| Page | Shows |
|---|---|
| `/backoffice/` | A search for entities, and the entity models of the application |
| `/backoffice/search?q=` | The entities found by a text: the entity with it as entity id, the entities having it as an id, e.g. a client id, and the entities indexed by it |
| `/backoffice/models/{id}` | The latest entities of an entity model, 25 at a time. Entity ids are time ordered, so they are listed in the order they were created. |
| `/backoffice/entities/{id}` | An entity: its events with their data, its ids and index keys, the event it waits for and the entities it refers to and is referred to by |

The data of an event is shown as its data type marshalled it: JSON indented, HTTP messages as text, and other bytes as
text if they are UTF-8 and as hexadecimal otherwise. Entity ids in the data are links to the entities.

An entity refers to other entities by their entity ids in the data of its events. An inbox request refers to the
entity its request was processed on in its `Request accepted` event, for example. An entity is referred to by the
entities having an index event with its entity id as key: its outbox requests by `Process referenced` of the outbox,
and its inbox requests by `Process referenced` of the inbox (see [HTTP inboxes](http-inbox.md#the-requests-of-an-entity)).
A related entity is expanded in place, loaded by htmx, so a graph can be followed on a single page.

The pages are in the colours of this site, and in its dark scheme if the system is dark. They are sent with a
Content-Security-Policy allowing scripts, styles and images of the backoffice only, so nothing is loaded from other
sites, and htmx is
configured not to evaluate scripts or add styles. The sign-out is a form posted to `/backoffice/signout`, which the
`SameSite=Lax` session cookie protects from other sites.

## Signing in

Users sign in with the authorization code flow and PKCE, the backoffice being a client of the provider, confidential
with a client secret or public without one:

1. A page requested without a session, or with the cookie of no session, starts a sign-in in a new `BackofficeSession`.
   The user is redirected to the provider with the entity id of the session as `state`, and a secret, from which the
   PKCE verifier and the nonce are derived, is kept with the session and in a cookie named after it, only sent to
   `/backoffice/callback`.
2. The provider redirects the user back to `/backoffice/callback`, which is an event of the session. The session
   checks the cookie against its secret, which binds the sign-in to the browser it was started in, and dispatches the
   token request redeeming the code, an at-most-once outbox request, so that a code is never redeemed twice, also if
   the transition is computed again.
3. The outbox tells the session the response of the provider. The session verifies the ID token with the keys of the
   provider and against its nonce, and decides by the claims: if the configured claim has one of the permitted
   values, e.g. the claim `roles` the value `backoffice`, the user is signed in, with a new token in the session cookie,
   and sent back to the page first requested. Otherwise the session records that the sign-in was refused, with the
   claims of the user. The browser's request at the callback waits for this answer.

A sign-in failing at the provider, e.g. as the user cancelled, or of a browser without the cookie, fails the session
with a page telling the user.

The provider is found by [OpenID Connect Discovery](https://openid.net/specs/openid-connect-discovery-1_0.html) at the
first sign-in rather than at startup, so the backoffice starts also when its provider is not up yet, e.g. the identity
provider it is the backoffice of. The backoffice is a client of the provider by the
[`OpenIdConnectClient`](http-inbox.md#signing-users-in-with-openid-connect) of `http-inbox`, which also fetches the keys
of the provider again when it signs an ID token with a new key. Every page viewed is an event of the session, rendered by its transition while the
session is valid. A session which has expired or been signed out of removes the cookie and redirects to the page
requested, which then starts a new sign-in. A request of htmx for a part of a page is answered with `HX-Refresh`
instead, so the page is reloaded.

## Running

`<app>-backoffice` serves the backoffice on `$PORT` until terminated, and `<app>-backoffice migrate` creates its schema
and grants its role read access to the application's, and exits. The Compose files of the apps run their backoffices
next to them, on port 8081, see the [identity provider](identity-provider.md#backoffice) and the
[hello world demo](hello-world.md#backoffice). Their images are built from the repository root, e.g.:

```shell
docker build -f apps/identity-provider-backoffice/Dockerfile -t tits-identity-provider-backoffice .
```

The [deploy](azure-functions.md) module doesn't yet deploy a backoffice to Azure Functions, as it creates a database
for every application, while a backoffice uses the database of its application
([#282](https://github.com/the-backend-project/backend/issues/282)).

## Configuration

The backoffice is configured by environment variables:

| Variable | Default | Description |
|---|---|---|
| `PORT` | `8080` | Port to serve HTTP on |
| `DATABASE` | `mssql` | `mssql` or `pgsql`, as the application's |
| `DATABASE_HOST`, `DATABASE_PORT`, `DATABASE_NAME`, `DATABASE_AUTHENTICATION`, `DATABASE_USER`, `DATABASE_PASSWORD`, `DATABASE_ACCESS_TOKEN`, `DATABASE_TRUST_SERVER_CERTIFICATE`, `DATABASE_POOL_SIZE`, `DATABASE_QUERY_TIMEOUT_SECONDS` | | The database of the application, and how the backoffice logs in to it, as for the [hello world demo](hello-world.md#configuration) |
| `DATABASE_SCHEMA` | The application's schema with `_backoffice` | The backoffice's own schema, with its sessions |
| `APPLICATION_SCHEMA` | The application's default | The application's schema, which the backoffice reads |
| `DATABASE_ROLE` | | The backoffice's role, given access to its schema and read access to the application's. Required by `migrate` only. |
| `BACKOFFICE_ISSUER` | | The issuer of the OpenID Connect provider. Required to serve. |
| `BACKOFFICE_URL` | | The URL of the backoffice, e.g. `https://orders-backoffice.example.com`. Its `/backoffice/callback` is the redirect URI to register at the provider. Cookies are `Secure` with an `https` URL. |
| `BACKOFFICE_CLIENT_ID` | | The client id of the backoffice at the provider |
| `BACKOFFICE_CLIENT_SECRET` | | Its client secret, or none for a public client |
| `BACKOFFICE_SCOPE` | `openid email profile` | The scope requested |
| `BACKOFFICE_CLAIM` | | The claim of the ID token which permits a user, e.g. `email`, `groups` or `roles` |
| `BACKOFFICE_CLAIM_VALUES` | | The values of the claim permitted, separated by commas. A claim with several values, e.g. the roles of a user, needs one of them. |
| `BACKOFFICE_SESSION_LIFETIME` | `PT8H` | How long a user stays signed in, as an ISO 8601 duration |

### Which claim to permit users by

Permit users by a claim the provider controls and means for authorisation, such as the `roles` of the app or the
`groups` of the user, rather than by their email address. With Microsoft Entra ID, for example, register an app with the
redirect URI, define an app role `backoffice` and assign it to the users of the backoffice, and set `BACKOFFICE_CLAIM`
to `roles` and `BACKOFFICE_CLAIM_VALUES` to `backoffice`. Removing the role of a user, or the user from the group,
then takes the backoffice from the user at the next sign-in, at the latest when the session expires.

The `email` claim is weak for authorisation: a provider may let users set or change it, need not verify it, and may give
the same address to more than one account. Microsoft advises against authorising by it, and with Microsoft Entra ID it
is an [optional claim](https://learn.microsoft.com/entra/identity-platform/optional-claims), not verified. Permit users
by `email` only if the provider verifies the addresses and doesn't let users choose them, e.g. a provider of your own
organisation's accounts.

## Gaps

- The backoffice only reads. Actions on entities, such as redispatching an outbox request, are left out
  ([#281](https://github.com/the-backend-project/backend/issues/281)).
- The backoffice shows the entities of the application, not its own sessions, which are in its own schema and in its
  [monitoring](monitoring.md) events.
- Every request and response is stored, credentials and pages included, and every request creates entities
  ([#285](https://github.com/the-backend-project/backend/issues/285)).
- The current state of an entity is not shown, as it is computed by the transitions, not stored. Its last event tells
  it.
- A `RequestRouting` entity has no reference to the inbox request it routed a request to, so the two aren't linked.
- An entity of an entity model the application no longer has is listed, but its events are shown with the ids of
  their event types and their data as bytes.
