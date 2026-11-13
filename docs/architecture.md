# Architecture

An application built with the server is a set of [entity models](entities.md), each a finite state machine, given to a
`StateMachine`. The state machine stores the entities as event logs in a database.

The application receives requests through [HTTP inboxes](http-inbox.md) and sends requests to other systems through
[HTTP outboxes](http-outbox.md).
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
| [http-inbox](http-inbox.md)                                           | Inboxes: entity models receiving HTTP requests and routing them to the entities of an application |
| [http-outbox](http-outbox.md)                                         | Outboxes: entity models sending HTTP requests at least once or at most once                    |
| [test-api](testing.md)                                                | An API for tests to look into and manipulate the database of a state machine                   |
| diagram                                                               | State diagrams and ER diagrams of a state machine's model                                      |
| [templates](cardpayments.md)                                          | Ready-made entity models of a domain, such as card payments                                    |

An application depends on core, the database module of its database, and http-inbox and http-outbox if it receives or
sends HTTP requests. See the [apps](apps.md) for complete applications.

## Diagrams

The diagram module draws the state diagram of an entity model with PlantUML, and an ER diagram of a state machine's
complete model, where the entity models are related through the triggers of their transitions. The diagrams of the
[card payment template](cardpayments.md) are generated this way when the documentation is built.
