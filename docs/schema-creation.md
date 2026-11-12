# Schema creation

The state machine creates its [schema](data-model.md) if it does not exist, and verifies it if it does. This is done by
`Database.createSchema`, implemented by `CreateSchema` in [database-mssql](../database-mssql) and
[database-pgsql-jdbc](../database-pgsql-jdbc), whose `CreateSchema` the reactive PostgreSQL [modules](databases.md)
use too, and called either:

* by the `StateMachine` constructor, when it is given a schema data source and a role, or
* by a separate admin process, such as `demo migrate` in the [cloud native demo](cloud-native.md), in which case the
  state machine is given no schema data source.

The schema data source needs credentials allowed to create the schema, which the state machine's own data source does
not. The role is granted access to the tables.

An existing schema is verified by comparing the checksum in its [Metadata](data-model.md#metadata) table with that of
the running code's table definitions. If they differ, the creation fails, and so does the startup of a state machine
creating its schema.

## Concurrent startup

Several state machines may start at the same time on a schema that does not exist yet, e.g. the replicas of a new
deployment, and would compete for its creation. Only one of them must create it, and the others must wait for it and
then verify it. `CreateSchema` does so in the following steps, on a single connection:

1. Read the checksum without a lock. If the schema exists, verify it and stop. This is the common case, as the schema
   only has to be created once.
2. Get an exclusive lock on the schema name, waiting for any state machine creating the schema.
3. Read the checksum again, as another state machine may have created the schema while this one waited for the lock.
   If the schema exists, verify it.
4. Otherwise create the schema, its tables and its `Metadata` row in one transaction, so that a failure leaves no
   partially created schema behind.
5. Release the lock.

The check without the lock is done in auto-commit mode. The connection's auto-commit mode and isolation level are
restored afterwards, so that a connection pool which does not reset them gets the connection back as it lent it.

The lock is named after the schema, `CreateSchema <schema>`, so state machines on different schemas do not wait for
each other.

| | SQL Server | PostgreSQL |
|---|---|---|
| Lock | Application lock, `sp_getapplock` | Advisory lock, `pg_advisory_xact_lock` on the hash of the name |
| Lock owner | The session, released explicitly with `sp_releaseapplock` | The transaction, released when it ends |
| Existence check | `OBJECT_ID` of the `Metadata` table | `pg_tables` |

On SQL Server the lock is owned by the session, as the driver begins a transaction implicitly, with the first statement
that needs one, and getting the lock is not such a statement.

### Why the schema is checked before the lock

Checking without the lock spares state machines the wait for each other when the schema exists. More importantly, a
lock that is held for longer than expected, e.g. by a process that died (see below), then only blocks the startup of
state machines on a schema that does not exist yet.

The check without the lock is safe, as it can not mistake a schema being created for one that exists:

* On PostgreSQL the tables of a transaction are not visible to others until it is committed. A state machine reading
  them while another creates them finds no schema, and goes on to wait for the lock.
* On SQL Server `OBJECT_ID` finds a table that is not committed yet, but reading from it waits for the transaction
  that created it. If that transaction is rolled back, the read fails, and the state machine goes on to the lock
  instead of failing.

### Seeing the schema after the lock on PostgreSQL

The check after the lock must see a schema that another state machine committed while this one waited for the lock.
On PostgreSQL two things could hide it.

**The catalog cache.** Name lookups such as `to_regclass('schema."Metadata"')` or a `::regclass` cast resolve the name
through the session's catalog cache, which also remembers names that were not found. The cache is brought up to date
with the catalog changes of other sessions when the session processes their invalidation messages, which it does at
the start of a transaction and when it locks a table, but not when it gets an advisory lock. The check without the
lock caches that the table does not exist. The transaction of the locked path then begins before the wait, as the
lock is its first statement, and so does not see the creation either. A `to_regclass` after the lock would thus still
find no table, and the creation would fail with `relation "Metadata" already exists`.

`CreateSchema` therefore looks the table up by querying `pg_catalog.pg_tables`, an ordinary query of the catalog
tables, which reads them with the statement's snapshot rather than through the cache. The concurrency test in
`StorageTest` fails every time with `to_regclass`.

**The snapshot.** That query sees the committed creation as each statement gets a new snapshot at the read committed
isolation level, PostgreSQL's default. At repeatable read or serializable, the snapshot would be taken by the first
statement, the one getting the lock, before the wait. `CreateSchema` therefore sets read committed for the locked
transaction, in case the schema data source is configured otherwise.

The check without the lock is not affected by either: it runs before any lock, and a schema created by others either
is visible to it or is created after it, in which case the locked path finds it.

## When a process dies holding the lock

The database releases the lock and rolls back the creation when it finds the connection closed. When the process
crashes or is killed, its operating system closes the connection, and the lock is released right away.

When the whole host or its network goes away, nothing closes the connection, and the database only finds out by TCP
keepalive. SQL Server's `KeepAlive` setting is 30 seconds by default. PostgreSQL's `tcp_keepalives_idle` defaults to
that of the operating system, which on Linux is over two hours. Set it lower on PostgreSQL servers where this matters.

Until the lock is released, state machines on a schema that does not exist yet can not create it. Waiting for the lock
is limited by the query timeout of the schema data source (`DatabaseClient.Config.queryTimeout`), and the `StateMachine`
constructor gives up on the schema creation after 20 seconds, so their startup fails, and is retried by whatever
restarts them, until the lock is released. State machines on an existing schema are not affected, as they verify it
without the lock.
