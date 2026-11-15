---
hide:
  - navigation
  - toc
---

# The Integratable Transaction Server

<p class="tagline">A Java library for event-sourced backends: model your domain as state machines, and let the
library take care of the transactions, the integrations and the event log.</p>

You write domain models in plain Java: the states of a payment, an order or a subscription, the events that move it
between them, and the requests it sends to other systems. The library appends every event to an immutable event log,
takes the transitions in database transactions, receives and deduplicates HTTP requests, and sends requests to other
systems reliably. You need no framework, no message broker and no application server, only a JVM and a SQL database.

The library follows the principles of domain-driven design. The model is written in the language of the domain: its
entities have an identity and a lifecycle, and its events are named after what has happened in the domain, such as
`Merchant suspended`. Persistence, messaging and HTTP are left to the library, so the model holds nothing but the
domain logic, and the state diagrams generated from it can be read by the domain experts too. See the
[design principles](principles.md#domain-driven-design).

[Get started :material-arrow-right:](getting-started.md){ .md-button .md-button--primary }
[Read the design principles](principles.md){ .md-button }

## What you get

<div class="grid cards" markdown>

-   :material-state-machine:{ .lg } **Entities as state machines**

    ---

    Each entity follows the states and transitions of its model, and nothing else. Its current state is derived from
    its [event log](data-model.md), so you can always see how it got there.

-   :material-file-tree:{ .lg } **Declarative models**

    ---

    [Transitions](transition-models.md) are declared with a builder rather than coded as procedures, so a model can be
    read, tested and drawn as [diagrams](cardpayments.md) from the source.

-   :material-send-check:{ .lg } **Reliable integrations**

    ---

    [Requests to other systems](http-outbox.md) are stored in the same transaction as the change that caused them, then
    sent at least once, or at most once with rollback.

-   :material-inbox-arrow-down:{ .lg } **Safe retries from clients**

    ---

    [Incoming requests](http-inbox.md) with a message id are deduplicated, so a client that retries a request gets the
    first response instead of making a second payment.

-   :material-lightning-bolt:{ .lg } **Reactive and stateless**

    ---

    Built on Reactor, with nothing kept in memory between requests, so you can [run as many instances](scaling.md) as
    you need against the same database.

-   :material-cloud-outline:{ .lg } **Cloud native**

    ---

    Apps compile to native executables that boot in tens of milliseconds, and can
    [scale to zero on Azure Functions](azure-functions.md).

</div>

## Built to last

An application built on the library needs little maintenance and doesn't age quickly. There is not much in it that
can break or go out of date. See the [design principles](principles.md) for the details.

* **A small, robust tech stack.** Java, Reactor and a SQL database. There is no framework, message broker, cache or
  application server. Fewer moving parts mean fewer upgrades, fewer outages and less to learn for whoever
  maintains the application next.
* **A small footprint.** Apps compile to native executables that boot in tens of milliseconds and run in container
  images built from `scratch`, containing nothing they don't declare. Every dependency is verified against a pinned
  checksum.
* **An immutable event log.** Events are appended, never changed, and the state of an entity is derived from them. A
  bug can be understood from what happened, not guessed at from what is left.
* **All or nothing.** Everything an event leads to is stored in one transaction or not at all, and changes that
  conflict are retried from the new state rather than overwritten.
* **Diagrams that are never out of date.** The [state diagrams](diagrams.md) are generated from the models, so the
  documentation of the domain always matches the code that runs.

## A taste of it

A greeting is created by every `GET /`, and its transition to the `Greeted` state responds with a page saying hello:

```java
onEvent(GreetingRequested).to(States.Greeted)
    .assemble(c -> c.log().entityId().toString())
    .trigger(GreetingResponseCreated).with(id -> id).on(RequestDispatching).identifiedBy(entityIdFromSession())
    .output()
```

Run it with its database, then visit [http://localhost:8080/](http://localhost:8080/):

```shell
cd apps/hello-world
docker compose up --build
```

[Getting started](getting-started.md) walks through the whole application.

## Where to next

<div class="grid cards" markdown>

-   :material-rocket-launch:{ .lg } **[Getting started](getting-started.md)**

    Add the libraries to a Maven project, declare a model, run it against a database and test it.

-   :material-sitemap:{ .lg } **[Architecture](architecture.md)**

    How the state machine, the HTTP inboxes and outboxes and the databases fit together.

-   :material-puzzle:{ .lg } **[Templates](cardpayments.md)**

    Ready-made models of a domain, starting with card payments and their PSD2 and PSD3 compliance.

-   :material-apps:{ .lg } **[Apps](apps.md)**

    Complete applications, an OAuth 2.0 identity provider and a hello world demo, and how to deploy them to Azure.

-   :material-database:{ .lg } **[Databases](databases.md)**

    SQL Server, Azure SQL or PostgreSQL, through JDBC, R2DBC or Vert.x.

-   :material-test-tube:{ .lg } **[Testing](testing.md)**

    A test API to look into the entities and force their states, without depending on the tables.

-   :material-bell-alert:{ .lg } **[Monitoring](monitoring.md)**

    Events with a severity, written for the platform to collect and raise alerts on.

-   :material-source-pull:{ .lg } **[Contributing](contributing.md)**

    How the repository is laid out, where to find an issue to work on, and how to build, test and open a pull request.

</div>
