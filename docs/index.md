---
hide:
  - navigation
  - toc
---

# The Integratable Transaction Server

<p class="tagline">Model your business as state machines, and let the server take care of the transactions, the
integrations and the history.</p>

Every payment, order or client in your application is an entity with a state, and every change to it is an event,
stored for good. You declare the states and the transitions between them. The server receives the requests, takes the
transitions, stores the events, and sends the requests to other systems, reliably and without losing track of
anything along the way.

[Explore the architecture :material-arrow-right:](architecture.md){ .md-button .md-button--primary }
[Try the hello world demo](hello-world.md){ .md-button }

## Why you'll like it

<div class="grid cards" markdown>

-   :material-state-machine:{ .lg } **Entities as state machines**

    ---

    Each entity follows the states and transitions of its model, and nothing else. Its current state is derived from
    its events, so you always know how it got there.

-   :material-file-tree:{ .lg } **Declarative models**

    ---

    Transitions are declared, not coded as procedures, so the model can be read, tested, and drawn as
    [diagrams](cardpayments.md) straight from the source.

-   :material-send-check:{ .lg } **Integrations you can trust**

    ---

    Requests to other systems are stored in the same transaction as the change that caused them, then sent at least
    once, or at most once with rollback.

-   :material-inbox-arrow-down:{ .lg } **Safe retries from clients**

    ---

    Incoming requests with a message id are deduplicated, so a client retrying a request gets the first response
    rather than a second payment.

-   :material-lightning-bolt:{ .lg } **Reactive and stateless**

    ---

    Built on Reactor, with nothing kept in memory between requests, so you can run as many instances as you need on the
    same database.

-   :material-cloud-outline:{ .lg } **Cloud native**

    ---

    Apps compile to native executables which boot in tens of milliseconds, and
    [scale to zero on Azure Functions](azure-functions.md).

</div>

## A taste of it

A greeting is created by every `GET /`, and its transition to the `Greeted` state responds with a page saying hello:

```java
onEvent(Greet).to(States.Greeted)
    .assemble(c -> c.log().entityId().toString())
    .trigger(RespondGreeting).with(id -> id).on(RequestDispatching).identifiedBy(entityIdFromSession())
    .output()
```

Run it with its database, then visit [http://localhost:8080/](http://localhost:8080/):

```shell
cd apps/hello-world
docker compose up --build
```

## Where to next

<div class="grid cards" markdown>

-   :material-sitemap:{ .lg } **[Architecture](architecture.md)**

    How the state machine, the HTTP inboxes and outboxes and the databases fit together.

-   :material-puzzle:{ .lg } **[Templates](cardpayments.md)**

    Ready-made models of a domain, starting with card payments and their PSD2 and PSD3 compliance.

-   :material-apps:{ .lg } **[Apps](apps.md)**

    Complete applications, an OAuth 2.0 identity provider and a hello world demo, and how to deploy them to Azure.

-   :material-database:{ .lg } **[Databases](databases.md)**

    SQL Server, Azure SQL or PostgreSQL, through JDBC, R2DBC or Vert.x.

-   :material-history:{ .lg } **[Data model](data-model.md)**

    Event logs as the source of truth, and the few tables holding the work still to be done.

-   :material-inbox-arrow-down:{ .lg } **[HTTP inboxes](http-inbox.md)**

    Requests from clients, routed to the entities of an application, deduplicated and authorised by access tokens.

-   :material-send:{ .lg } **[HTTP outboxes](http-outbox.md)**

    Requests to other systems, sent at least once or at most once by the transactional outbox pattern.

-   :material-test-tube:{ .lg } **[Testing](testing.md)**

    A test API to look into the entities and force their states, without depending on the tables.

</div>
