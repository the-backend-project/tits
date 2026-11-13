# Existing ids

An id event claims an id for an entity, and is unique among the events of its type (see the
[data model](data-model.md#idevent)). Whether the id is taken can't be known for certain until the changes
are stored, since another instance can claim it in the meantime. The state machine therefore calculates the changes as
if the id were free, and stores them. If an id is found to be taken, the database rejects the transaction on the
unique index, and the state machine

1. reads the event log of the entity holding the id,
2. tells the [`Calculation`](transition-models.md#collecting-the-changes) that the id exists, and
3. calculates the changes again, from the [step](transition-models.md#steps) triggering the id.

The steps before the trigger are not executed again, but get the results recorded for them, so the calculation of the
changes doesn't start over. When the id event is triggered again, its result is an `IdExistsStep`, with the entity
holding the id, instead of the event being accepted. The `ProcessResult` of the trigger is then `IdExists`, which the
transition can act on. The [identity provider](identity-provider.md) rejects the registration of a client whose id is
taken this way:

```java
onEvent(RegistrationRequested).to(States.Accepted)
    .assemble(c -> tuple(c.input(), UUID.randomUUID()))
    .trigger(ClientIdClaimed).with(d -> d.t1().clientId()).on(ClientIds).identifiedBy(d -> entityId(d.t2(), AlwaysCreate))
    .when(d -> d.t2().isIdExists()).then(rejectRegistration, d -> d.t1().t1())
    .otherwise(acceptRegistration, d -> d.t1())
```

If a step before the trigger was rejected, all the steps are executed again, since whether a rejection is handled is
decided by the steps after it. The changes are not calculated again if the id is not triggered by any step, or if it
is found to exist even after they were calculated with it existing; the event then fails.
