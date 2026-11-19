# Backoffice

The `backoffice` module is a web application in which users look up the entities of an application and follow the
graph of an entity: its event log, its ids and index keys, the events it waits for, the entities its events refer to,
and the entities referring to it, such as its [inbox](http-inbox.md) and [outbox](http-outbox.md) requests. Users sign
in with [OpenID Connect](https://openid.net/specs/openid-connect-core-1_0.html), and only those whose ID token has a
permitted claim value get in. The backoffice only reads.

The backoffice of an application is an application of its own, packaged and deployed apart from the application it
looks into, with the application's entity models, which it needs to unmarshal the data of the events. It is built
with the HTTP inbox and a state machine of its own, whose entities are the sessions of its users, in a schema of its
own: every sign-in is an entity whose event log tells who signed in and which pages they viewed. It reads the
application's schema with a role which may only read it. The pages are HTML, made interactive with
[htmx](https://htmx.org), which the backoffice serves itself.

Both [apps](apps.md) have one, `apps/identity-provider-backoffice` and `apps/hello-world-backoffice`. The backoffice of
the [identity provider](identity-provider.md#backoffice) signs its users in with the identity provider.

Kept apart, the backoffice doesn't add to the footprint or the attack surface of the application. It can be deployed
where only its users reach it, e.g. on a private network, while the application is public, and a bug in it can't
change the application's data. Its sessions and the load of its users stay out of the application's schema.

## Model

| Entity | Description |
|---|---|
| `BackofficeSession` | A sign-in of a user: who signed in, or was refused, every page viewed, and the sign-out or expiry. Found by the hash of the token of the session cookie, which is an id event of `BackofficeTokens`. |

<object data="../images/com.github.thxmasj.backend.backoffice.BackofficeSession.svg" type="image/svg+xml"></object>

[Open the diagram in full size](images/com.github.thxmasj.backend.backoffice.BackofficeSession.svg)

A refused or failed sign-in is an event of [severity](monitoring.md#severity) `Warning`, so attempts to get in show
up in the monitoring. A session expires after its lifetime, or after 30,000 events, as the event numbers of an entity
are `SMALLINT`.

## The backoffice of an application

An application's backoffice is a module of its own, which depends on the application for its entity models and on
`backoffice`, and whose main method runs `BackofficeApp` with the name of the application, its default schema and the
transitions of its state machine:

```java
public static void main(String[] args) {
  BackofficeApp.run("Orders", "orders", Orders.transitions(), args, System.getenv());
}
```

The application exports the package of its entity models to the module of its backoffice, e.g.
`exports com.example.orders to com.example.orders.backoffice;`. The backoffice is built and deployed like the
[apps](apps.md): a native executable in an image built from `scratch`, with a migrate admin process. It is named after
the application, e.g. "Orders backoffice", in the header and the title of its pages.

The `Backoffice` filter in front of the inbox of its state machine is the edge between the browser and the state
machine, as the [edge](identity-provider.md) of the identity provider is: it signs the users in with the provider, and
passes what it found to the state machine in headers which it removes from every request it receives. The cookies, the
codes and ID tokens of the provider, and the token of the session never enter the event log, only the claims of the
user and the hash of the token. It serves the pages under `/backoffice`, and nothing else but `/health`.

## The database

The backoffice uses the database of the application, with two schemas:

| Schema | Default | The backoffice's role may |
|---|---|---|
| The backoffice's own, `DATABASE_SCHEMA` | The application's with `_backoffice` added, e.g. `orders_backoffice` | Read and write it, as a state machine its own schema |
| The application's, `APPLICATION_SCHEMA` | The application's default, e.g. `orders` | Only read it |

Its migrate admin process, run as an administrator after the application's, creates its schema for its role,
`DATABASE_ROLE`, as the application's creates the application's, and grants the role read access to the application's
schema with `Database.grantRead`: `SELECT` on the schema on SQL Server, and `USAGE` on the schema and `SELECT` on its
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

1. A page requested without a session starts a sign-in: a new `BackofficeSession`, whose entity id is the `state` of
   the authorization request. The user is redirected to the provider, with a secret, from which the PKCE verifier and
   the nonce are derived, in a cookie only sent to `/backoffice/callback`.
2. The provider redirects the user back to `/backoffice/callback`, where the code is redeemed and the ID token
   verified with the keys of the provider.
3. If the configured claim of the ID token has one of the permitted values, e.g. the claim `roles` the value
   `backoffice`, the user is signed in: a new token is set in the session cookie, and the user is sent back to the page
   first requested. Otherwise the sign-in is refused, with the claims of the user recorded.

The provider is found by [OpenID Connect Discovery](https://openid.net/specs/openid-connect-discovery-1_0.html) at the
first sign-in rather than at startup, so the backoffice starts also when its provider is not up yet, e.g. the identity
provider it is the backoffice of. Every
page viewed is an event of the session, which the state machine accepts only while the session is valid, so a session
which has expired or been signed out of is refused, and the user signs in again. A request of htmx for a part of a
page is answered with `HX-Refresh`, so the page is reloaded and the user signs in.

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
([#282](https://github.com/the-backend-project/tits/issues/282)).

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

With Microsoft Entra ID, for example, register an app with the redirect URI, define an app role `backoffice` and
assign it to the users of the backoffice, and set `BACKOFFICE_CLAIM` to `roles` and `BACKOFFICE_CLAIM_VALUES` to
`backoffice`.

## Gaps

- The backoffice only reads. Actions on entities, such as redispatching an outbox request, are left out
  ([#281](https://github.com/the-backend-project/tits/issues/281)).
- The backoffice shows the entities of the application, not its own sessions, which are in its own schema and in its
  [monitoring](monitoring.md) events.
- The current state of an entity is not shown, as it is computed by the transitions, not stored. Its last event tells
  it.
- A `RequestRouting` entity has no reference to the inbox request it routed a request to, so the two aren't linked.
- An entity of an entity model the application no longer has is listed, but its events are shown with the ids of
  their event types and their data as bytes.
