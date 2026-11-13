# Transition models

## Input and output of an event type

An event type has two type parameters, `EventType<I, O>`, and so does the transition model of a transition on it,
`TransitionModel<I, O>`:

* **`I`, the input**, is the data an event of the type is triggered with: by `with(...)` of the transition triggering
  it, by the caller of `StateMachine.onEvent`, or by a delayed or conditional trigger, which stores it until the
  trigger fires. The transition calculates its event from it.
* **`O`, the output**, is the data of the event the transition stores in the entity's event log. `output(f)` calculates
  it from the data of the last step, and `output()` stores the event without data. The output is also what a
  transition triggering the event gets back.

`BasicEventType.of(name, id, dataType)` gives an event type whose input and output are the same, and
`BasicEventType.of(name, id)` one with neither (`Void`). The [identity provider](identity-provider.md) has event types
of all kinds:

| Event type            | `I`                    | `O`            |
|-----------------------|------------------------|----------------|
| `Register`            | `Registration`         | `Registration` |
| `RequestRegistration` | `Registration`         | `Void`         |
| `AcceptRegistration`  | `(Registration, UUID)` | `Registration` |
| `ClientId`            | `String`               | `String`       |

## Steps

`TransitionModelBuilder` builds the transition model. It starts with `onEvent(type).to(state)`, every call after it,
such as `assemble`, `trigger` or `when`, adds a step, and `output()` ends it with the model.

When an event enters an entity, the model calculates the steps of the transition one by one, each from the one before
it. A step is a `TransitionStep<T>`, where `T` is its output: the data the next step is calculated from. The chain
starts with an `InputStep`, whose output is the input `I`, and ends with an `OutputStep`, whose output is the result of
the transition, a `ProcessResult<O>` with the new event if it is accepted. The steps in between turn one into the
other:

```mermaid
flowchart LR
    in(["Input I"]) --> input
    subgraph model ["TransitionModel#lt;I, O#gt;"]
        direction LR
        input["InputStep<br>I"] -- "assemble" --> assemble["AssembleStep<br>T"]
        assemble -- "trigger(...).on(...)" --> trigger["TriggerStep<br>(T, ProcessResult#lt;O1#gt;)"]
        trigger -- "output(f)" --> output["OutputStep<br>ProcessResult#lt;O#gt;"]
    end
    log[("Event log")] -.-> input
    output --> event(["Event with O"])
    event --> log
```

The input step also has what the other steps need besides the data: the entity's event log, its current state, the
timestamp, and the `Calculation` the step is part of. The output of each step is:

| Step                     | Added by                      | Output                                                                                        |
|--------------------------|-------------------------------|-----------------------------------------------------------------------------------------------|
| `InputStep`              | The event entering the entity | The input `I`                                                                                 |
| `AssembleStep`           | `assemble`                    | What the assembler makes of the input and the event log                                       |
| `TriggerStep`            | `trigger(...).on(...)`        | The output of the step before it, paired with the `ProcessResult<O1>` of the event triggered  |
| `DelayedTriggerStep`     | `trigger(...).after(...)`     | The output of the step before it. The step has the delayed event to store                     |
| `ConditionalTriggerStep` | `trigger(...).when(...)`      | The output of the step before it. The step has the conditional trigger to store               |
| `ActionStep`             | `trigger(action)`             | The output of the step before it. The step has the action to run after the changes are stored |
| `ChoiceStep`             | `when(...).then(...)`         | The output of the step before it, after the steps of the chosen transition                    |
| `OutputStep`             | `output`                      | The `ProcessResult<O>` of the transition                                                      |

## Triggering an event on another entity

An event triggered on another entity continues the chain into the transition of that entity. The data of the trigger,
`with(...)`, is the input `I1` of the triggered event type, and becomes the output of the `InputStep` of the triggered
transition. Its steps follow up to its `OutputStep`, whose `ProcessResult<O1>` the `TriggerStep` pairs with the data
it had before the trigger, and the chain goes on in the triggering transition.

An order reserving the items of an order line from their stock shows how the data goes from one entity to the
other. `Place` takes the order line as input and stores it as output, and `Reserve` takes the quantity to reserve as
input and stores the quantity left in stock as output:

```java
record OrderLine(UUID item, int quantity) {}
record Reservation(int quantity) {}
record Remaining(int quantity) {}

EventType<OrderLine, OrderLine> Place = BasicEventType.of("Place", placeId, DataType.json(OrderLine.class));
EventType<Reservation, Remaining> Reserve = BasicEventType.of(
    "Reserve",
    reserveId,
    DataType.json(Reservation.class),
    DataType.json(Remaining.class)
);
```

The transition of the order triggers `Reserve` on the stock of the item, and the transition of the stock calculates
the quantity left from its event log:

```java
onEvent(Place).to(OrderStates.Placed)
    .assembleInput()
    .trigger(Reserve).with(line -> new Reservation(line.quantity())).on(Stock).identifiedBy(line -> entityId(line.item()))
    .output(d -> d.t1())

onEvent(Reserve).toSelf()
    .assemble((reservation, log) -> new Remaining(remaining(log) - reservation.quantity()))
    .output(remaining -> remaining)
```

```mermaid
flowchart TB
    subgraph order ["Order: Place, EventType#lt;OrderLine, OrderLine#gt;"]
        direction LR
        o1["InputStep<br>OrderLine"] -- "assembleInput()" --> o2["AssembleStep<br>OrderLine"]
        o3["TriggerStep<br>(OrderLine, ProcessResult#lt;Remaining#gt;)"] -- "output(f)" --> o4["OutputStep<br>Accepted Place, with OrderLine"]
    end
    subgraph stock ["Stock: Reserve, EventType#lt;Reservation, Remaining#gt;"]
        direction LR
        s1["InputStep<br>Reservation"] -- "assemble" --> s2["AssembleStep<br>Remaining"]
        s2 -- "output(f)" --> s3["OutputStep<br>Accepted Reserve, with Remaining"]
    end
    o2 -- "trigger(Reserve)" --> s1
    s3 --> o3
```

Every step refers to the one before it, `previous`, so the steps of both transitions are stacked in one chain, with
the steps of the triggered transition nested between the step triggering it and the `TriggerStep`. Below, the first
step is on top, every step refers to the one above it, and the result of the calculation is the step at the bottom:

<div class="step-stack">
  <div class="step-stack-title">Order: Place</div>
  <div class="step"><code>Input</code><span>OrderLine</span></div>
  <div class="step"><code>Assemble</code><span>OrderLine</span></div>
  <div class="step-stack">
    <div class="step-stack-title">Stock: Reserve</div>
    <div class="step"><code>Input</code><span>Reservation</span></div>
    <div class="step"><code>Assemble</code><span>Remaining</span></div>
    <div class="step"><code>Output</code><span>ProcessResult&lt;Remaining&gt;</span></div>
  </div>
  <div class="step"><code>Trigger</code><span>(OrderLine, ProcessResult&lt;Remaining&gt;)</span></div>
  <div class="step"><code>Output</code><span>ProcessResult&lt;OrderLine&gt;</span></div>
</div>

The `TriggerStep` also refers to the step before the trigger, `outer`, here the `AssembleStep` of `Place`, whose
output it pairs with the result of the triggered event. A step finds the `InputStep` of its own transition, with the
event log and the timestamp, by skipping over the nested transitions, from their `OutputStep` back to their
`InputStep`.

A triggered event isn't always accepted. Instead of an `OutputStep`, the triggered transition can then end with one
of these, whose `ProcessResult` the triggering transition can act on, e.g. with `when(...)`:

| Step            | When                                                                                |
|-----------------|-------------------------------------------------------------------------------------|
| `RejectedStep`  | The event is rejected, e.g. since the entity has no transition on it from its state |
| `UnknownIdStep` | The entity of the event is not found                                                |
| `PendingStep`   | The event is circular, i.e. on an entity already changed by the calculation         |
| `IdExistsStep`  | The id of an id event is held by another entity (see [existing ids](existing-ids.md)) |

## Choosing a transition

`when(predicate).then(model, f)` chooses another transition of the same entity, if the predicate holds for the
output of the step before it. `f` gives the input of the chosen transition's event type, and the steps of the chosen
transition follow, in the chain, up to its `OutputStep`. `otherwise(model, f)` ends the transition with the
transition chosen if no other one was. The event of the chosen transition is then the event stored; the output of the
transition itself is a `Completed` result referring to it.

The identity provider registers a client by `RequestRegistration`, whose output is `Void`, and the event stored is
either `RejectRegistration` or `AcceptRegistration`, depending on whether the client id is taken:

```mermaid
flowchart LR
    in(["Registration"]) --> i1
    subgraph request ["RequestRegistration, EventType#lt;Registration, Void#gt;"]
        direction LR
        i1["InputStep<br>Registration"] -- "assemble" --> a1["AssembleStep<br>(Registration, UUID)"]
        a1 -- "trigger(ClientId)" --> t1["TriggerStep<br>((Registration, UUID), ProcessResult#lt;String#gt;)"]
        o1["OutputStep<br>Completed"]
    end
    subgraph reject ["RejectRegistration, EventType#lt;Registration, Registration#gt;"]
        direction LR
        r1["InputStep<br>Registration"] --> r2["..."] --> r3["OutputStep<br>Accepted, with Registration"]
    end
    subgraph accept ["AcceptRegistration, EventType#lt;(Registration, UUID), Registration#gt;"]
        direction LR
        c1["InputStep<br>(Registration, UUID)"] --> c2["..."] --> c3["OutputStep<br>Accepted, with Registration"]
    end
    t1 -- "when(id exists)" --> r1
    t1 -- "otherwise" --> c1
    r3 -- "ChoiceStep" --> o1
    c3 -- "ChoiceStep" --> o1
```

## Collecting the changes

When the calculation is done, the changes to store, i.e. the accepted events, the delayed events and the conditional
triggers, and the actions to run, are collected by following the chain back from its last step to its first. In the
order above, that gives the `Place` and `Reserve` events.

Every step is taken through the `Calculation` of the event, which records the result of the step. That makes it
possible to calculate the changes again from a step in the middle of the chain, without executing the steps before it
again, such as an assembler reading from another system.
