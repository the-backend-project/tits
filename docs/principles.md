# Design principles

The library is for backends that hold money, contracts or other things that matter, and that are expected to run for
many years while the people maintaining them change. It favours being stable, robust and maintainable over being
quick to write. The principles below explain the choices you meet in the rest of the documentation, and contributions
are expected to follow them.

## The history is the source of truth

An entity's events are stored in its [event log](data-model.md) and are never updated or deleted. Its current state is
derived from them. The other tables only hold work still to be done, such as timeouts that are due later.

* When something goes wrong, the events show what happened and in which order, instead of leaving you to guess from the
  last state stored.
* Entity models and event types are identified in the database by UUIDs that are fixed in the code, not by their names
  or by Java class names. You can rename and refactor freely, and the old events can still be read. Once a UUID has
  been used in production, it must never change.
* Schema creation checks an existing schema against the table definitions of the running code (see
  [Schema creation](schema-creation.md)). If they differ, it fails instead of carrying on with a schema the code
  doesn't match.

## All or nothing

Everything an event leads to, across all the entities involved, is stored in one database transaction or not at all
(see the [architecture](architecture.md)). If another instance changed one of the entities in the meantime, the
transaction fails on the event number and the event is [processed again](entities.md#processing-an-event) from the new
state. No change is ever silently overwritten.

Anything that can't be part of the transaction, such as an HTTP request to another system, is stored in the
transaction and done after it commits. Its outcome comes back as a new event. That is why
[outboxes](http-outbox.md) can promise at-least-once or at-most-once delivery, and why
[inboxes](http-inbox.md) can deduplicate retried requests.

## No state in memory

Nothing is kept in memory between requests. Any instance can serve any request, instances can be started and stopped
at any time, and a crash loses nothing that was committed (see [Scaling](scaling.md)). There are no caches to warm up
and no local state to keep consistent.

## Declared, not coded

Transitions are declared with `TransitionModelBuilder`, not written as procedures (see
[Transition models](transition-models.md)). Because a model is data, it can be:

* reviewed as a whole, state by state,
* drawn as [state diagrams](diagrams.md) generated from the source, so the diagrams never fall out of date, and
* tested through its behaviour, with the [test API](testing.md) instead of the tables.

Names follow fixed conventions, such as event types named in the past tense (see
[Naming event types](entities.md#naming-event-types)). Someone new to a model can then read it without having to
learn each author's style.

## A stable platform

* **Java LTS releases only.** The libraries target [Java 25](modules.md#java-version) and move only to the next LTS
  release, once GraalVM supports it. An application built on them is never forced onto a release that is about to lose
  its updates.
* **Standard building blocks.** The libraries are plain Java modules, built with Maven and run on any JVM or as
  GraalVM native executables. They need no framework, application server or message broker, only a SQL database:
  SQL Server, Azure SQL or PostgreSQL (see [Databases](databases.md)).
* **A controlled supply chain.** Every dependency and plugin is verified against a pinned SHA-256 checksum, and the
  apps are statically linked executables in container images built from `scratch`, so an image contains only what it
  declares.

## Observable by design

Every event can be written to the platform the application runs on, with a severity that the transition declares (see
[Monitoring](monitoring.md)). Operating the application relies on the same events that make up its history, not on
log statements added after the fact.

## Documented with the code

The documentation in this site is kept up to date in the same pull request as the code it describes. A change that
makes a page wrong isn't finished until the page has been fixed (see [Contributing](contributing.md)).
