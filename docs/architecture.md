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

## Transition models and steps

`TransitionModelBuilder` builds a `TransitionModel`, one for each transition of an entity model. The builder starts
with `onEvent(type).to(state)`, and every call after it, such as `assemble`, `trigger` or `when`, adds a step to the
model. `output()` ends the builder and gives the model.

A transition model calculates the steps of its transition, each from the step before it. A step is a `TransitionStep`,
which refers to the step before it and has the output of the step, the data the next step is calculated from. The
chain of steps of a transition starts with an `InputStep` and ends with an `OutputStep`:

| Step                     | Taken by                       | Output                                                                                                                                |
|--------------------------|--------------------------------|---------------------------------------------------------------------------------------------------------------------------------------|
| `InputStep`              | The event entering the entity  | The data of the event. It also has the entity's event log, the current state, the timestamp and the `Calculation` it is part of        |
| `AssembleStep`           | `assemble`                     | The data assembled from the input and the event log                                                                                   |
| `TriggerStep`            | `trigger(...).on(...)`         | The data together with the `ProcessResult` of the event triggered on the other entity                                                 |
| `DelayedTriggerStep`     | `trigger(...).after(...)`      | The data of the step before it. The step has the delayed event to store                                                               |
| `ConditionalTriggerStep` | `trigger(...).when(...)`       | The data of the step before it. The step has the conditional trigger to store                                                         |
| `ActionStep`             | `trigger(action)`              | The data of the step before it. The step has the action to run after the changes are stored                                           |
| `ChoiceStep`             | `when(...).then(...)`          | The data of the step before it, after the steps of the chosen transition                                                              |
| `OutputStep`             | `output`                       | The `ProcessResult` of the transition: the new event if it is accepted                                                                |
| `RejectedStep`           | A triggered event              | The rejection of the triggered event, e.g. since the entity has no transition on it from its state                                    |
| `UnknownIdStep`          | A triggered event              | The failure of the triggered event to find its entity                                                                                 |
| `PendingStep`            | A triggered event              | The triggered event being circular, i.e. on an entity already changed by the calculation                                              |
| `IdExistsStep`           | A triggered id event           | The id existing, held by another entity (see below)                                                                                   |

An event triggered on another entity continues the chain: the `InputStep` of the triggered transition refers to the
step triggering it, and its steps follow up to its `OutputStep`, after which the `TriggerStep` of the triggering
transition goes on with the result. When the calculation is done, the changes to store, i.e. the accepted events, the
delayed events and the conditional triggers, and the actions to run, are collected by following the chain from its
last step back to the first.

Every step is taken through the `Calculation` of the event, which records the result of the step. That makes it
possible to calculate the changes again from a step in the middle of the chain, without executing the steps before it
again, such as an assembler reading from another system.

## Existing ids

An id event claims an id for an entity, and is unique among the events of its type (see the
[data model](data-model.md#idevent)). Whether the id is taken can't be known for certain until the changes
are stored, since another instance can claim it in the meantime. The state machine therefore calculates the changes as
if the id were free, and stores them. If an id is found to be taken, the database rejects the transaction on the
unique index, and the state machine

1. reads the event log of the entity holding the id,
2. tells the `Calculation` that the id exists, and
3. calculates the changes again, from the step triggering the id.

The steps before the trigger are not executed again, but get the results recorded for them, so the calculation of the
changes doesn't start over. When the id event is triggered again, its result is an `IdExistsStep`, with the entity
holding the id, instead of the event being accepted. The `ProcessResult` of the trigger is then `IdExists`, which the
transition can act on. The [identity provider](identity-provider.md) rejects the registration of a client whose id is
taken this way:

```java
onEvent(RequestRegistration).to(States.Accepted)
    .assemble(c -> tuple(c.input(), UUID.randomUUID()))
    .trigger(ClientId).with(d -> d.t1().clientId()).on(ClientIds).identifiedBy(d -> entityId(d.t2(), AlwaysCreate))
    .when(d -> d.t2().isIdExists()).then(rejectRegistration, d -> d.t1().t1())
    .otherwise(acceptRegistration, d -> d.t1())
```

If a step before the trigger was rejected, all the steps are executed again, since whether a rejection is handled is
decided by the steps after it. The changes are not calculated again if the id is not triggered by any step, or if it
is found to exist even after they were calculated with it existing; the event then fails.

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

### Access tokens

Routes requiring an access token, e.g. one issued by the [identity provider](identity-provider.md), take
`HttpInbox.bearerToken(scopes...)` as their authorizer, which authorizes the `sub` of the token if it has the scopes.
The token is verified by a `BearerTokenFilter` on the listener, with an `AccessTokenVerifier` given the keys of the
issuer, its issuer URI and optionally the audience the tokens must have:

```java
var verifier = AccessTokenVerifier.fetchingKeys(
    URI.create("https://idp.example.com/.well-known/jwks.json"),
    URI.create("https://idp.example.com"),
    "payments-api",
    Clock.systemUTC()
);
new HttpInboxListener(stateMachine).filter(new BearerTokenFilter(verifier, "payments-api")).listen(8080);
```

The verifier accepts JWT access tokens as profiled by [RFC 9068](https://www.rfc-editor.org/rfc/rfc9068): of type
`at+jwt`, signed with RS256 by a key of the issuer, of the issuer, not expired, for the audience if one is given, and
with the claims `sub` and `client_id`. A request with an invalid token is answered with 401 by the filter. A valid
token is replaced by its claims in the headers `X-Token-Subject`, `X-Token-Client-Id` and `X-Token-Scope`, which
requests can't set themselves. A request without a token is refused by the routes requiring one with 400, as any
request failing a route's authorizer.

The token is verified before the request enters the state machine for two reasons: the event log records the claims
of the token but never the token, and the routing of a request, which may be calculated again later, does not depend
on whether the token has expired since. The keys are fetched once, so the verifier must be created again to see keys
the issuer has added since.

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
