# Testing

Tests of an application built on the state machine mostly drive it the way its clients do, e.g. by sending HTTP
requests, and assert on what comes out. Sometimes they also need to look inside: to find the entity a request created,
to check the events it got, to see whether it is waiting for a delayed event, or to put an entity in a specific state
before a scenario starts. The `test-api` module gives them a test API for that, so that they don't have to query the
database tables directly.

## Why a test API

In *Clean Architecture* (Robert C. Martin, 2017), chapter 28, "The Test Boundary", the section "The Testing API"
argues that tests should talk to the system through an API built for testing, not through its user interface or its
internal classes:

* **Structural coupling.** Tests that know the structure of the system break when the structure changes, and so they
  resist refactoring. Tests that query the tables of the [data model](data-model.md), or construct the classes that
  do, break when a table or class changes, although the behaviour they test has not. The test API hides that structure:
  tests ask for the event log of an entity, not for the rows of `Event` joined with `Entity`.
* **Superpowers.** A test API may do what clients can't, such as bypassing security, skipping expensive resources, and
  forcing the system into specific states. Here that means triggering a delayed event now instead of waiting for its
  deadline, cancelling pending work left over from other tests, and appending events that no transition has accepted.
* **Security.** These powers are dangerous in production, so the test API lives in a component of its own that only
  tests depend on and that is never deployed with the application.

## Setting it up

Depend on the module in test scope only:

```xml
<dependency>
  <groupId>com.github.thxmasj.backend</groupId>
  <artifactId>test-api</artifactId>
  <version>${backend.version}</version>
  <scope>test</scope>
</dependency>
```

Create a `TestApi` with a data source for the application's database, the schema name, and the same transitions as
the state machine is given. The transitions tell the test API the entity models and event types, so it can return the
events with their data unmarshalled, for any entity model.

```java
var api = new TestApi(dataSource, "Payments", transitions, Clock.systemUTC());

EventLog payment = api.eventLogById(PaymentId, paymentId).block();
assertEquals(Authorised, payment.events().getLast().type());
```

The data source needs no more privileges than the role the state machine runs with. If the clock of the state machine
is a mock clock, give the test API the same one.

## Operations

All operations are reactive, like the rest of the state machine, and complete empty when there is nothing to return.

### Looking into the entities

| Operation | Gives |
| --- | --- |
| `eventLog(entityId)` | The event log of an entity, with the entity model it was created with |
| `eventLog(entityModel, entityId)` | The event log of an entity of an entity model, including id and index entities |
| `eventLogById(idType, id)` | The event log of the entity having an id, e.g. a merchant id or the message id of a request |
| `id(entityId, idType)` | The id of a type of an entity, e.g. the token reference of a token |
| `entitiesByIndex(indexType, key)` | The event logs of the entities indexed by a key, e.g. the outbox requests of a payment |
| `lastIndexed(entityModel, indexType, group)` | The event log of the last entity of a group, e.g. the open settlement of a merchant |
| `delayedTrigger(entityId)` | The event the entity is waiting for, with its deadline and the number of attempts so far |
| `conditionTriggers(entityId)` | The events the entity is waiting for on conditions on other entities |

### Forcing states

| Operation | Does |
| --- | --- |
| `expireDelayedTrigger(entityId)` | Makes the event the entity is waiting for due now, so it is triggered without waiting for its deadline |
| `deleteDelayedTriggers()` | Cancels the events all entities are waiting for, e.g. so that a scenario starts with no pending dispatches |
| `append(entityModel, entityId, eventType, data)` | Appends an event without any transition, e.g. to start a scenario with an open settlement having a given batch number |

`append` stores the event as if a transition had accepted it, so the event the entity was waiting for is no longer
triggered, but it doesn't validate the event against the entity's state and triggers no other events. Appending
inconsistent events gives entities that the state machine can't process, so prefer driving the system to the state
when that is practical.

## What it doesn't cover

The test API covers the tables of the state machine. Tables and sequences an application creates for itself, such as a
sequence of system trace audit numbers, are part of the application, which should give its tests access to them in the
same spirit.
