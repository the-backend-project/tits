# Modules

| Module                                                                | Contains                                                                                       |
|-----------------------------------------------------------------------|------------------------------------------------------------------------------------------------|
| core                                                                  | The state machine, the builder of transition models, and the interface of the databases        |
| [database-mssql, database-pgsql-*](databases.md)                      | The SQL of a database and the driver it is reached through, one module per database and driver |
| [http-inbox](http-inbox.md)                                           | Inboxes: entity models receiving HTTP requests and routing them to the entities of an application |
| [http-outbox](http-outbox.md)                                         | Outboxes: entity models sending HTTP requests at least once or at most once                    |
| [test-api](testing.md)                                                | An API for tests to look into and manipulate the database of a state machine                   |
| [diagram](diagrams.md)                                                | State diagrams and ER diagrams of a state machine's model                                      |
| [templates](cardpayments.md)                                          | Ready-made entity models of a domain, such as card payments                                    |

An application depends on core, the database module of its database, and http-inbox and http-outbox if it receives or
sends HTTP requests. See the [apps](apps.md) for complete applications.
