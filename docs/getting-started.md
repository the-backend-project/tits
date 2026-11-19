# Getting started

This page builds a small application step by step: the [hello world demo](hello-world.md), whose complete source is in
`apps/hello-world`. Every `GET /` creates a `Greeting` entity, whose transition responds with a page saying hello. Along
the way it shows what an application consists of and where to read more about each part.

## Requirements

* Java 25 or later and Maven 3.9 or later. The libraries target [Java 25](modules.md#java-version).
* A database: SQL Server, Azure SQL or PostgreSQL (see [Databases](databases.md)). For development, the scripts in
  `bin` start one in a container: `bin/start-mssql.sh` on port 11433 or `bin/start-postgres.sh` on port 15432.

## Adding the libraries

No release has been published to a Maven repository yet, so install the libraries from source into your local Maven
repository first:

```shell
git clone https://github.com/the-backend-project/tits.git
cd tits
mvn install -DskipTests -pl database-mssql,http-inbox -am
```

Then depend on the module of your database, and on the HTTP inbox if the application receives HTTP requests. Both
bring in the core module:

```xml
<dependency>
  <groupId>com.github.thxmasj.backend</groupId>
  <artifactId>http-inbox</artifactId>
  <version>${backend.version}</version>
</dependency>
<dependency>
  <groupId>com.github.thxmasj.backend</groupId>
  <artifactId>database-mssql</artifactId>
  <version>${backend.version}</version>
</dependency>
```

Set `backend.version` to the version in the repository's `pom.xml`. For PostgreSQL, use `database-pgsql-jdbc` or one of
the reactive modules instead of `database-mssql`. See [Modules](modules.md) for what each module contains.

The libraries are Java modules. An application that is a module itself requires them in its `module-info.java`:

```java
module com.example.helloworld {
  requires com.github.thxmasj.backend.database.mssql;
  requires com.github.thxmasj.backend.http.inbox;
}
```

## Declaring the model

An [entity model](entities.md) has a name and a UUID, a set of states and the event types that move its entities
between them. The UUIDs are what is stored in the database, so generate them once and never change them (see the
[design principles](principles.md#safe-to-change)):

```java
EntityModel Greeting = EntityModel.of("Greeting", UUID.fromString("8a4c1d36-4f0e-4b8e-9a47-2f5f0c0b6d11"));

enum States implements State {
  Greeted
}

EventType<Void, Void> GreetingRequested = BasicEventType.of(
    "Greeting requested",
    UUID.fromString("0b7f8f43-37a3-4f26-9a5c-6f3ad6f1c2a4")
);

ResponseEventType<String> GreetingResponseCreated = new ResponseEventType<>(
    "Greeting response created",
    UUID.fromString("d2c9b8f1-6a0e-4a43-8f8e-1c0f6e3b7a52"),
    DataType.string()
);
```

The requests the application receives are entities too, of the [HTTP inbox](http-inbox.md). An inbox request matches
requests and says which event they trigger on which entity, here `GreetingRequested` on a new `Greeting` for every
`GET /`, and turns the response event into an HTTP response:

```java
HttpInboxRequest<Void> GreetingRequest = HttpInboxRequest
    .matching(m -> m.requestLine().matches("GET /(\\?.*)?"))
    .noContent()
    .process(GreetingRequested, Greeting, newEntity())
    .response(
        GreetingResponseCreated,
        c -> new HttpResponseMessage(
            200,
            "OK",
            Map.of("Content-Type", "text/html;charset=UTF-8", "X-Correlation-Id", c.correlationId()),
            page(c.input(), c.correlationId()).getBytes(StandardCharsets.UTF_8)
        )
    )
    .build("GreetingRequest", UUID.fromString("5e3d4a8c-0f2b-4c6e-b1d7-9a8f7e6d5c4b"));
```

<object data="../images/com.github.thxmasj.backend.helloworld.GreetingRequest.svg" type="image/svg+xml"></object>

[Open the GreetingRequest diagram in full size](images/com.github.thxmasj.backend.helloworld.GreetingRequest.svg)

The [transitions](transition-models.md) say what happens on each event type in each state. A greeting starts in
`Begin`, and `GreetingRequested` takes it to `Greeted`. On the way, the transition triggers the response on the request
that is waiting for it, `HttpInbox.Request`, which stands for whichever inbox request the greeting was requested by:

```java
static Map<EntityModel, Map<State, List<TransitionModel<?, ?>>>> transitions() {
  return HttpInbox.transitions(List.of(GreetingRequest), Map.of(
      Greeting, Map.of(
          Begin, List.of(
              onEvent(GreetingRequested).to(States.Greeted)
                  .assemble(c -> c.log().entityId().toString())
                  .trigger(GreetingResponseCreated).with(id -> id).on(Request).identifiedBy(entityIdFromSession())
                  .output()
          ),
          States.Greeted, List.of()
      )
  ));
}
```

The diagram of `Greeting`, drawn from these transitions:

<object data="../images/com.github.thxmasj.backend.helloworld.Greeting.svg" type="image/svg+xml"></object>

[Open the Greeting diagram in full size](images/com.github.thxmasj.backend.helloworld.Greeting.svg)

`HttpInbox.transitions` adds the entity models of the inbox to those of the application: the inbox requests,
`RequestRouting`, which routes a request to the inbox request matching it, `MessageIds` and `RequestIndex`, which
indexes the requests by the entities they were processed on. It also checks that the inbox request takes the response
the greeting gives, `GreetingResponseCreated`. Both are in `Greetings.java` in the demo.

## Running it

Build a data source with the database module, give it to a `StateMachine` with your domain model, and serve HTTP with
an `HttpInboxListener`:

```java
Database database = new MssqlDatabase();
StateMachine stateMachine = new StateMachine(
    null,                         // no delayer
    Greetings.transitions(),
    new DataSourceBuilder(database, config.database()).buildPooled(),
    null,                         // the schema is created by a separate admin process
    config.schema(),
    null,
    Clock.systemUTC(),
    new JsonLinesListener()       // writes the events to stdout, one line of JSON each
);
DisposableServer server = new HttpInboxListener(stateMachine).listen(config.port());
```

The schema is created before the application starts by `database.createSchema(...)`, run with credentials that are
allowed to create it. The state machine can also create the schema itself when it is given a second data source and a
role (see [Schema creation](schema-creation.md)).

To run the demo with its database in containers, see [Running](hello-world.md#running) on its page.

## Testing it

The demo's `HelloWorldTest` runs the application against the test database the way it is deployed: it creates a
schema with a random name, starts the state machine and the listener, and sends HTTP requests to it. Most tests of an
application should drive it like this, the way its clients do.

To look into the entities, or to put them in a given state before a scenario, add the `test-api` module in test scope
and use its `TestApi` instead of querying the tables (see [Testing](testing.md)):

```java
var api = new TestApi(dataSource, schema, Greetings.transitions(), Clock.systemUTC());
```

## Next steps

* [Architecture](architecture.md): how the state machine, the inboxes, the outboxes and the database work together.
* [Transition models](transition-models.md): assembling data, triggering events on other entities and choosing
  between transitions.
* [HTTP outboxes](http-outbox.md): sending requests to other systems at least once or at most once.
* [Monitoring](monitoring.md): the severity of events and alerting on them.
* [Card payments](cardpayments.md): a complete, realistic model to learn from.
