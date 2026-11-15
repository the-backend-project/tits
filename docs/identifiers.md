# Identifiers

Every entity is identified by a UUID, which is created for it when the entity is created. Entity ids are time-ordered
UUIDs.

## Time-ordered UUIDs

A time-ordered UUID (version 7, [RFC 9562](https://www.rfc-editor.org/rfc/rfc9562#name-uuid-version-7)) starts with the
time it is created at, as 48 bits of milliseconds since the Unix epoch, followed by the version and 74 random bits.
Ids created later are greater, so they sort in the order they are created, to the millisecond.

```
019a6b2c-1d3e-7f40-8a1b-2c3d4e5f6071
└───────────┘ │
  time (ms)    version 7, then random bits
```

`Identifiers.newId()` creates one, with the same layout as `UUID.ofEpochMillis`, which was added in Java 26 and so can't
be used while the native images are built for Java 25. It is what the state machine uses for new entities.

## Ids of new entities

An entity can't be created with an id of the application's choosing: a random UUID, or one taken from a request, would
land at a random place in the indexes (see below). The entity selectors that may create an entity therefore take its id
as an `EntityId`, which can't be made from a UUID. There are two ways to get one:

* `EntityId.newId()`, a new time-ordered id.
* `c.entityId()` in a transition, the id of the entity the transition is on.

| Selector                           | Entity                                                      |
|------------------------------------|-------------------------------------------------------------|
| `newEntityId()`                    | A new entity with a new time-ordered id                     |
| `newEntityId(entityId)`            | A new entity with the `EntityId`                            |
| `entityId(entityId, creationMode)` | The entity with the `EntityId`, created if the mode says so |
| `entityId(id)`                     | The existing entity with the UUID or `EntityId`             |

An entity shares the id of the entity it belongs to, e.g. an [id entity](existing-ids.md) or an index entity:

```java
.assemble(c -> tuple(c.input(), c.entityId()))
.trigger(AccountNumberClaimed).with(Tuple2::t1).on(Ids).identifiedBy(d -> entityId(d.t2(), CreateIfNotExists))
```

Two entities that are not id or index entities can't share an id, as their events are in the same table. The
[identity provider](identity-provider.md) therefore creates a client and its id entity with a new id, which it makes
before the id entity is created, so that it can create the client with it later in the transition:

```java
.assemble(c -> tuple(c.input(), EntityId.newId()))
.trigger(ClientIdClaimed).with(d -> d.t1().clientId()).on(ClientIds).identifiedBy(d -> newEntityId(d.t2()))
```

An existing entity is selected by any UUID with `entityId(id)`, so entities created with random UUIDs before the ids
were time-ordered can still be found. An HTTP route's `parseEntityId` only selects existing entities, as its id comes
from the request.

## Why not random UUIDs

The entity id leads the primary key of every table with entity rows: `Entity`, `Event`, `IdEvent` and `IndexEvent`
(see the [data model](data-model.md)). Both SQL Server and PostgreSQL keep these keys in B-tree indexes, whose leaf
pages hold the keys in order.

A random UUID (version 4) lands at a random place in the index. At scale this degrades every insert:

* Each insert goes to a random leaf page, so the whole index is the working set. Once it outgrows memory, inserts read
  pages from disk.
* A full page is split in two half-full pages, so the index ends up larger and fragmented, and scans read more pages.
* Each changed page is written back by the next checkpoint, and PostgreSQL writes the whole page to its write-ahead
  log on the first change after a checkpoint. Random inserts change many pages, each with little new data.

A time-ordered UUID lands at the end of the index, next to the ids created just before it. The inserts fill the last
pages, which stay in memory, and pages are split at the end only, as for an increasing number. Unlike a sequence, the
ids are still created without a round trip to the database, and are unique across instances without coordination.

Within a millisecond the ids are ordered by their random bits, so ids created in the same millisecond, or on instances
with clocks that differ, may not sort in the order they were created. This doesn't matter for the indexes, and the
state machine doesn't rely on the order of ids for anything else: the events of an entity are ordered by their numbers.

An id tells when its entity was created, so anyone who sees an entity's id, e.g. in a URL, can tell its age.

## Storing entity ids

PostgreSQL stores an entity id in its `UUID` type, and compares UUIDs byte by byte from the first, so entity ids are in
time order in its indexes.

SQL Server's `UNIQUEIDENTIFIER` compares the last six bytes first, where a time-ordered UUID has random bits. It would
insert them at random places, as if they were random UUIDs. The state machine therefore stores entity ids as
`BINARY(16)` on SQL Server, the 16 bytes in the order of the UUID's string form, which SQL Server compares byte by byte
from the first. The ids show as binaries in SQL Server's tools, e.g. `0x019A6B2C1D3E7F408A1B2C3D4E5F6071`, which is the
UUID without its hyphens. To find the events of an entity by its id:

```sql
SELECT * FROM [schema].[Event]
WHERE EntityId = CONVERT(BINARY(16), REPLACE('019a6b2c-1d3e-7f40-8a1b-2c3d4e5f6071', '-', ''), 2)
ORDER BY EventNumber;
```
