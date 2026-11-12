# Architecture

An application built with the server is a set of entity models, each a finite state machine, given to a
`StateMachine`. The state machine stores the entities as event logs in a database.

The application receives requests through HTTP inboxes and sends requests to other systems through HTTP outboxes.
They are state machines with entities too, given to the `StateMachine` along with the entity models of the
application. Every request received or sent is an entity with a state and an event log of its own. It is stored in the
same database and the same transactions as the entities of the application it belongs to. The state machine doesn't
treat a request any differently from a payment or an order.

Everything is reactive, built on [Reactor](https://projectreactor.io), and no state is kept in memory between
requests, so any number of instances can serve the same database.

```mermaid
flowchart LR
    client([Clients]) -- HTTP --> inbox
    subgraph sm [State machine]
        direction LR
        inbox[Inbox entities] --> app[Application entities]
        app --> outbox[Outbox entities]
    end
    outbox -- HTTP --> remote([Other systems])
    sm <--> db[(Database)]
```

## Modules

| Module                                                                | Contains                                                                                       |
|-----------------------------------------------------------------------|------------------------------------------------------------------------------------------------|
| core                                                                  | The state machine, the builder of transition models, and the interface of the databases        |
| [database-mssql, database-pgsql-*](databases.md)                      | The SQL of a database and the driver it is reached through, one module per database and driver |
| http-inbox                                                            | Inboxes: entity models receiving HTTP requests and routing them to the entities of an application |
| http-outbox                                                           | Outboxes: entity models sending HTTP requests at least once or at most once                    |
| [test-api](testing.md)                                                | An API for tests to look into and manipulate the database of a state machine                   |
| diagram                                                               | State diagrams and ER diagrams of a state machine's model                                      |
| [templates](cardpayments.md)                                          | Ready-made entity models of a domain, such as card payments                                    |

An application depends on core, the database module of its database, and http-inbox and http-outbox if it receives or
sends HTTP requests. See the [apps](apps.md) for complete applications.

## Entities

An entity model has a set of states, and transitions between them on event types. An entity is an instance of an
entity model, identified by a UUID. Its events are stored in its event log, and its current state is the state its
events lead to from the initial state `Begin`. The event logs are the source of truth; see the
[data model](data-model.md).

The transitions of an entity model are declared with `TransitionModelBuilder`. A transition is taken on an event of a
type, assembles data from the event and the entity's event log, and can trigger events on other entities:

```java
onEvent(Greet).to(States.Greeted)
    .assemble(c -> c.log().entityId().toString())
    .trigger(RespondGreeting).with(id -> id).on(RequestDispatching).identifiedBy(entityIdFromSession())
    .output()
```

The entity an event is triggered on is selected by an `EntitySelector`: by its entity id, as a new entity, by an id it
has, or as the last one of a group it is indexed in. The ids and index keys of entities are events of their own, of
id entity models (`IdEntityModel`) and index entity models (`IndexEntityModel`). An id is unique among the ids of its
type, so an event claiming an id that is taken is rejected.

## Processing an event

An event enters the state machine through `StateMachine.onEvent`, from the HTTP inbox or from the application itself.
The state machine reads the event log of the selected entity, finds the transition from its current state on the
event's type, and follows the triggers of the transition from entity to entity. The new events of all the entities
involved are stored in one transaction, together with the triggers to be fired later. If another instance has
appended an event to one of the entities in the meantime, the transaction fails on the event number, and the event is
retried on the new event log.

A transition has four kinds of triggers:

* **Event triggers** trigger an event on another entity, in the same transaction.
* **Delayed triggers** (`.after(delay)`) trigger an event on the entity after a delay, unless it has received another
  event first. They are stored in the database and fired by a worker of every state machine instance. Timeouts and
  retries are built from them.
* **Conditional triggers** trigger an event when the last event of every referenced entity is one of the given types,
  e.g. when all the requests of a batch are completed. They are also fired by a worker.
* **Actions** are side effects outside the database, such as sending an HTTP request. They are run after the
  transaction is committed, and their outcome is fed back to the entity as a new event.

Since side effects only happen after the events causing them are stored, an entity always knows what it has started.
If the instance stops before an action completes, a delayed trigger stored with the action, such as the timeout of an
outbox request, moves the entity on.

## HTTP inboxes

An inbox is a set of entity models whose entities are the requests the application receives. Their transitions are
given to the state machine along with those of the application, and the inbox is drawn in the same kind of state
diagram (see [RequestRouting](images/com.github.thxmasj.backend.http.inbox.RequestRouting.svg) and
[RequestDispatching](images/com.github.thxmasj.backend.http.inbox.RequestDispatching.svg)).

The `HttpInboxListener` receives HTTP requests with reactor-netty and passes them to the entity models of the inbox.
A `RequestRouting` entity is created for every request, and matches it against the routes of the application. A route
validates the request, and gives the event type and entity it triggers. A `RequestDispatching` entity is created for
every routed request, which triggers the event on the application's entity and waits for it to respond. The response
is an event on the `RequestDispatching` entity, of a `ResponseEventType`, which the listener sends to the client.

Requests carrying a message id are deduplicated through the `MessageIds` id entity model: a retried request gets the
response of the first one, without triggering anything again. Filters on the listener can authenticate, log or rewrite
requests before they reach the inbox.

## HTTP outboxes

An application has an outbox for each kind of request it sends to other systems, e.g. one for authorisations and one
for captures. Each outbox is an entity model whose entities are the requests sent, and its state diagram tells how a
request goes from dispatched to accepted, rejected, retried or rolled back (see
[AtLeastOnce](images/com.github.thxmasj.backend.http.outbox.AtLeastOnce.svg) and
[AtMostOnce](images/com.github.thxmasj.backend.http.outbox.AtMostOnce.svg)).

A request's entity is created by a transition of the application, through a dispatcher, so the request is stored before it is sent, and is sent by an
action after the transaction is committed. The response is fed back as an event, and the outbox request triggers an
event on the entity which dispatched it. A request without a response times out by a delayed trigger stored when it
was dispatched.

* `AtLeastOnce` resends the request after a backoff, by delayed triggers, until it gets a response or its attempts
  run out. The receiver must therefore tolerate duplicates.
* `AtMostOnce` sends the request once. It can be rolled back, whether it was accepted or its outcome is unknown, e.g.
  after a timeout, by a rollback request, which is itself an `AtLeastOnce` request.

Requests of both kinds can be sent in batches.

## Diagrams

The diagram module draws the state diagram of an entity model with PlantUML, and an ER diagram of a state machine's
complete model, where the entity models are related through the triggers of their transitions. The diagrams of the
[card payment template](cardpayments.md) are generated this way when the documentation is built.
