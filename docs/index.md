---
hide:
  - navigation
  - toc
---

# The Integratable Transaction Server

<p class="tagline">A Java library for backends that must not lose track: model your business as state machines, and
let the library take care of the transactions, the integrations and the history.</p>

You write entity models in plain Java: the states of a payment, an order or a client, the events that move it between
them, and the requests it sends to other systems. The library stores every event in the entity's event log, takes the
transitions in database transactions, receives and deduplicates HTTP requests, and sends requests to other systems
reliably. You need no framework, no message broker and no application server, only a JVM and a SQL database.

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

Systems that move money or manage contracts run for many years, and the people maintaining them change. The library
is built for that kind of system. See the [design principles](principles.md) for the details.

* **The history is kept for good.** Events are never updated or deleted, and the state of an entity is derived from
  them. A bug can be understood from what happened, not guessed at from what is left.
* **Correctness comes before convenience.** Everything an event leads to is stored in one transaction or not at all,
  and changes that conflict are retried from the new state rather than overwritten.
* **It stays on long-term support releases.** The libraries target [Java 25](modules.md#java-version), the latest LTS
  release, and move only to the next one. Your application is never forced onto a short-lived release.
* **The models stay readable.** A model is a declaration you can review, test and draw, so it can still be understood
  long after its authors have moved on.
* **The builds are reproducible.** Every dependency and plugin is verified against a pinned checksum, and the apps run
  as statically linked executables in images built from `scratch`.

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
