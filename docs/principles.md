# Design principles

The library lets you write an application quickly and keep it running for years with little maintenance. You declare
the domain model, and the library does the plumbing: persistence, transactions, retries, integrations and monitoring.
The principles below explain the choices you meet in the rest of the documentation, and contributions are expected to
follow them.

## Domain-driven design

The model is written in the language of the domain, and nothing else goes into it:

* An entity has an identity and a lifecycle, which is the states of its [entity model](entities.md) and the
  transitions between them.
* An event records something that has happened in the domain, and is named the way the domain experts would say it,
  in the past tense, such as `Merchant suspended` (see [Naming event types](entities.md#naming-event-types)).
* Persistence, messaging and HTTP are not part of the model. The library stores the events, and sends and receives
  requests through [outboxes](http-outbox.md) and [inboxes](http-inbox.md) that are entity models of their own.
* The [state diagrams](diagrams.md) are generated from the model, so developers and domain experts look at the same
  picture, and it is never out of date.

## The event log is the source of truth

The [event log](data-model.md) is immutable: events are appended to it and never changed. The current state of an
entity is derived from its events.

## All or nothing

Everything an event leads to, across all the entities involved, is stored in one database transaction or not at all
(see the [architecture](architecture.md)). If another instance changed one of the entities in the meantime, the
transaction fails on the event number and the event is [processed again](entities.md#processing-an-event) from the new
state. No change is ever silently overwritten.

Anything that can't be part of the transaction, such as an HTTP request to another system, is stored in the
transaction and done after it commits. Its outcome comes back as a new event, in a transaction of its own but in
the same [session](sessions.md). That is why
[outboxes](http-outbox.md) can promise at-least-once or at-most-once delivery, and why
[inboxes](http-inbox.md) can deduplicate retried requests.

## No state in memory

Nothing is kept in memory between requests. Any instance can serve any request, instances can be started and stopped
at any time, and a crash loses nothing that was committed (see [Scaling](scaling.md)). There are no caches to warm up
and no local state to keep consistent.

## Safe to change

An application can be refactored without breaking what is already stored:

* Entity models and event types are identified in the database by UUIDs that are fixed in the code, not by their names
  or by Java class names. You can rename and restructure freely, and the old events can still be read. Once a UUID has
  been used in production, it must never change.
* Schema creation checks an existing schema against the table definitions of the running code (see
  [Schema creation](schema-creation.md)). If they differ, it fails instead of carrying on with a schema the code
  doesn't match.

## Declarative transitions

Transitions are declared in Java with `TransitionModelBuilder`: the event they are taken on, the state they lead to,
the data they assemble and the events they trigger (see [Transition models](transition-models.md)). The library reads
the declarations rather than running them as opaque procedures, so a model can be:

* reviewed as a whole, state by state,
* drawn as [state diagrams](diagrams.md), and
* tested through its behaviour, with the [test API](testing.md) instead of the tables.

## Observability built into the model

The severity of an event is an attribute of the transition that accepts it, declared in the model next to the state it
leads to, and `Info` unless said otherwise (see [Monitoring](monitoring.md#severity)). A refused token request is a
`Warning`, and an outbox request that is given up is an `Error`, because the model says so, not because someone
remembered to add a log statement. The events are written for the
platform the application runs on to collect and raise alerts on, so the alerts follow the same model as the
application.
