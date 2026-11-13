# Architecture

An application built with the server is a set of [entity models](entities.md), each a finite state machine. The
`StateMachine` runs them, and stores their entities as event logs in a database.

The application receives requests through [HTTP inboxes](http-inbox.md) and sends requests to other systems through
[HTTP outboxes](http-outbox.md). They are entity models too, and every request received or sent is an entity with a
state and an event log of its own. It is stored in the same database and the same transactions as the entities of the
application it belongs to. The state machine doesn't treat a request any differently from a payment or an order.

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
