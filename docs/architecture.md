# Architecture

The transaction server uses [entity models](entities.md) to handle events. Each entity model defines a finite state
machine: the states its entities can be in, and the transitions between them on types of events. The events of an
entity are stored in its event log in a database, and its current state is the state its events lead to.

An event is processed by a [transition](transition-models.md) of its entity, from the entity's current state. A
transition can trigger events on other entities, whose transitions are taken nested in it, and these can trigger
events on yet other entities in turn. All that an event leads to, the new events of every entity involved, is stored
in one database transaction, so either all of it is stored or none of it is. If another instance has changed one of
the entities in the meantime, the transaction fails and the event is
[processed again](entities.md#processing-an-event) from the new state.

What can't be part of the transaction is done around it. Events to be triggered later, such as timeouts, are stored in
the same transaction, and triggered by a transaction of their own when they are due. Side effects outside the
database, such as sending an HTTP request, are run after the transaction is committed, and their outcome is processed
as a new event, again in a transaction of its own.

The transaction server receives requests through [HTTP inboxes](http-inbox.md) and sends requests to other systems
through [HTTP outboxes](http-outbox.md). They are entity models too, and every request received or sent is an entity
with a state and an event log of its own. It is stored in the same database and the same transactions as the entities
it is received for or sent by, and the transaction server doesn't treat a request any differently from a payment or an
order.

Everything is reactive, built on [Reactor](https://projectreactor.io), and no state is kept in memory between
requests, so any number of instances can serve the same database. See [scaling](scaling.md).

```mermaid
flowchart LR
    client([Clients]) -- HTTP --> inbox
    subgraph sm [Transaction server]
        direction LR
        inbox[Inbox entities] --> app[Application entities]
        app --> outbox[Outbox entities]
    end
    outbox -- HTTP --> remote([Other systems])
    sm <--> db[(Database)]
```
