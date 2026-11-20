# Entities and events

## Entities

An entity model has a set of states, and transitions between them on event types. An entity is an instance of an
entity model, identified by a UUID. Its events are stored in its event log, and its current state is the state its
events lead to from the initial state `Begin`. The event logs are the source of truth; see the
[data model](data-model.md).

The transitions of an entity model are declared with `TransitionModelBuilder`. A transition is taken on an event of a
type, assembles data from the event and the entity's event log, and can trigger events on other entities:

```java
onEvent(GreetingRequested).to(States.Greeted)
    .assemble(c -> c.log().entityId().toString())
    .trigger(GreetingResponseCreated).with(id -> id).on(Request).identifiedBy(entityIdFromSession())
    .output()
```

An entity model carries its transitions, by the state they are taken from. `EntityModel.of(name, id, transitions)`
takes a method creating them, which is called when they are first asked for, so the transitions can refer to the model
itself and to other models declared the same way, e.g. constants which refer to each other:

```java
EntityModel Greeting = EntityModel.of("Greeting", UUID.fromString("..."), Greetings::transitions);
```

They are first asked for when the state machine is created, by which time the constants are all created. A trigger
on a model which isn't created yet, and so is null, fails as the transitions are created.

The state machine takes the transitions of each entity model from the model. It is given the entity models nothing
triggers events on, e.g. those of an [inbox](http-inbox.md), and runs them with the models their transitions trigger
events on, and so on, each once. A group triggered on adds no models, as its models are those of the group which are
run. When the state machine is created, it checks that every model it runs has an id of its own.

A model whose transitions depend on configuration, e.g. a lifetime, or the [outbox requests](http-outbox.md) it sends,
is created from the configuration rather than declared as a constant. That instance is the one triggered on, and
thereby run, so it is passed to the code triggering events on it, e.g. the inbox requests processing on it. Created
again, it is another instance with the same id, which may differ, e.g. be created from other configuration. The state
machine and the test API reject it, and only take the instance they run, which e.g. a test finds among the
`entityModels()` of the state machine.

The entity an event is triggered on is selected by an `EntitySelector`: by its entity id, as a new entity, by an id it
has, as the last one of a group it is indexed in, or as the last one referencing an id by an index event. The ids and
index keys of entities are events of their own, of id entity models (`IdEntityModel`) and index entity models
(`IndexEntityModel`). An id is unique among the ids of its type, so an event claiming an id that is taken is rejected.

## Naming event types

An event in an event log is something that has happened, so an event type is named in the past tense: the subject
the event is about, followed by a past participle, e.g. `Registration accepted`, `Merchant suspended` or
`Response received`. The name is in sentence case, and the constant holding the event type has the same words in
upper camel case:

```java
EventType<Registration, Registration> RegistrationRejected = BasicEventType.of(
    "Registration rejected",
    UUID.fromString("1a7e4c9d-2f63-4b05-8e9a-6d3c0b8f1e72"),
    DataType.json(Registration.class)
);
```

The subject keeps an event type apart from the state it leads to, which is a bare participle or adjective, e.g.
`TokenIssued` leads to `Issued`. A few kinds of event types follow a common pattern:

| Kind                                | Pattern                          | Example                                   |
|-------------------------------------|----------------------------------|-------------------------------------------|
| A request, e.g. from the HTTP inbox | `<subject> requested`            | `Token requested`, `Greeting requested`   |
| A request found to be invalid       | `<problem> detected`             | `Unknown merchant detected`               |
| An id of an id entity model         | `<id> claimed`                   | `Client id claimed`, `Message id claimed` |
| A key of an index entity model      | `<subject> referenced`/`indexed` | `Process referenced`, `Batch indexed`     |
| A read-only event type              | `<subject> looked up`            | `Merchant looked up`, `Status looked up`  |

Event types are identified by their id, not by their name, so an event type can be renamed without migrating the
events stored.

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

## Events and entities not stored

Every event is stored by default, which makes the event log the record of what happened. Some events are better left
out of it: one with a credential, e.g. a session cookie or a code, one with bulky data derived from other entities,
e.g. a rendered page, or one of no interest, e.g. a request for a static file. They can still be transitions, which are
tested, drawn in the state diagrams and told the [monitoring](monitoring.md), without being stored:

* **An event type which is not stored**, `BasicEventType.notStored(name, id, ...)`, takes part in its transition like
  any other: it is calculated, may trigger events on other entities, which are stored if they are of stored types, is
  given to the one who triggered it and is told the listener. It is not written to the event log, so the state of an
  entity must not depend on it: on an entity which is stored, its transitions stay in their state (`toSelf()`), which
  the state machine checks when it is created. The next event stored on the entity takes its event number. A
  read-only event type (`BasicEventType.ReadOnly`) is one.
* **An entity of the session only** is created by an event which is not stored, i.e. one from `Begin`, or is of an
  entity model whose `stored()` is false. None of its events are stored, whatever their types, so its transitions may
  go to any state. It is found by the transitions of the session, but not after it, so it can't have delayed or
  conditional triggers, which fire later: the change fails if it has one. An inbox request which is
  [not stored](http-inbox.md#requests-not-stored) is one.
* **A masked event** is stored with its output masked: `.masked(mask)` on a transition, after `to(...)`, gives the
  output as it is stored. The event in the session has the whole output, which its triggers, the one who triggered it
  and the transitions of the same session see, while the event log has the masked one, which is what the log of the
  entity has later. The [inbox](http-inbox.md#masking) masks the credentials of the requests it stores this way.

```java
// A page rendered from other entities, which is not stored
EventType<View, Page> PageViewed = BasicEventType.notStored("Page viewed", UUID.fromString("..."), DataType.json(View.class), DataType.json(Page.class));

// A secret kept in the browser, of which the event log only has the hash
onEvent(SignInStarted).to(States.SigningIn).masked(Start::withoutSecret)
```

What isn't stored can't be read back, so it must be something the entity doesn't need later: the state it is in, a
value a later transition reads from its log, or a request a retry is compared with. A rollback rolls back stored events
only. Keep in the event log what the record needs, e.g. the hash of a secret and the claims of a user signed in, and
leave out the secret, the token and the page.
