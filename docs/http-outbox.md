# HTTP outboxes

An application sends requests to other systems through outboxes. An outbox is an entity model whose entities are the
requests sent, so every request has a state and an event log of its own, stored in the same database as the entities
of the application.

An outbox delivers its requests either at least once (`AtLeastOnce`) or at most once (`AtMostOnce`). Which one fits
depends on what the receiver can tolerate: a duplicate, or not knowing whether the request arrived.

## The transactional outbox pattern

A transition sending a request to another system has two things to do: store the new state of the entity, and send
the request. They can't be done atomically, since the database and the other system don't share a transaction:

* If the request is sent first and the transaction then fails, the other system has acted on a request the
  application has no record of.
* If the transaction is committed first and the instance then stops before sending, the application believes it
  has sent a request which never left.

The transactional outbox pattern solves this by storing the request in the database, in the same transaction as the
change causing it, and sending it afterwards. The state machine does this for every outbox request:

1. The transition of the application triggers `Request dispatched` on a new entity of the outbox, with the request
   message as data. The outbox request is stored in the same transaction as the event of the application. If the
   transaction fails, nothing is sent.
2. The same transaction stores a delayed trigger, `TimeoutExpired`, after the inflight timeout of the outbox, and
   the action sending the request.
3. After the transaction is committed, the action sends the request. Its outcome, a response or a failure to
   connect, is fed back to the outbox request as an event, in a transaction of its own.
4. The outbox request triggers an event on the entity which dispatched it, e.g. with the response.

```mermaid
sequenceDiagram
    participant app as Application entity
    participant outbox as Outbox request
    participant db as Database
    participant remote as Other system
    app->>outbox: Request dispatched
    outbox->>db: Store both events and the timeout, in one transaction
    outbox->>remote: HTTP request, after the commit
    remote-->>outbox: HTTP response
    outbox->>db: Store the response
    outbox->>app: Callback, e.g. with the response
```

Since the request is stored before it is sent, the application always knows which requests it has started. What it
can't know is whether a request whose response never came was received: the instance may have stopped before
sending it, the connection may have broken, or the other system may have handled it without the response getting
back. In all these cases the delayed `TimeoutExpired` is fired, by any instance, and moves the request on. What happens
next is the delivery semantics of the outbox.

## Delivery semantics

| Outbox        | A request whose outcome is unknown is | The receiver must                    | Failures are handled by                         |
|---------------|---------------------------------------|--------------------------------------|-------------------------------------------------|
| `AtLeastOnce` | Sent again, after a backoff           | Tolerate duplicates, e.g. by an id   | Retrying until the attempts run out             |
| `AtMostOnce`  | Never sent again                      | Nothing                              | The application, e.g. by rolling the request back |

`AtLeastOnce` fits requests that are idempotent at the receiver, such as notifications carrying a message id, and
rollbacks. `AtMostOnce` fits requests that must not be repeated, such as authorising a card payment, where a duplicate
would reserve the amount twice. The outcome of an `AtMostOnce` request can be left unknown; the application then
decides what to do, e.g. roll the request back by a reversal, which is itself sent `AtLeastOnce`.

## AtLeastOnce

An `AtLeastOnce` request is sent again after a backoff when it has no response, or a response the receiver may give a
different answer to on a new attempt, until it is accepted or its attempts run out:

```mermaid
stateDiagram-v2
    [*] --> InFlight: Request dispatched
    InFlight --> Accepted: Response, accepted
    InFlight --> Compensating: Connection failed or dropped, timeout,<br>transient rejection or invalid response,<br>attempts available
    Compensating --> InFlight: Request redispatched, after the backoff
    InFlight --> Dead: Permanent rejection
    InFlight --> Dead: Attempts exhausted
    Accepted --> InFlight: Request redispatched, on demand
    Dead --> InFlight: Request redispatched, on demand
    Accepted --> [*]
    Dead --> [*]
```

| State          | Meaning                                                                                            |
|----------------|----------------------------------------------------------------------------------------------------|
| `InFlight`     | The request is sent, or about to be, and waits for its response                                    |
| `Compensating` | The attempt failed, and the request will be sent again after the backoff                           |
| `Accepted`     | The receiver accepted the request. The process is told by the success callback, if there is one   |
| `Dead`         | The receiver rejected the request permanently, or the attempts ran out                             |

Nothing tells the process of a request which is `Dead`. The events leading there have the
[severity](monitoring.md#severity) `Error`, so monitoring does. Once the cause is fixed, the request can be
[redispatched](#redispatching-an-accepted-or-dead-request).

The response is parsed by the content parser of the outbox, and the predicates of the outbox decide the outcome:

| Outcome of an attempt                       | Decided by                            | Next                                     |
|---------------------------------------------|---------------------------------------|------------------------------------------|
| A valid response, accepted                  | `isAccepted`                          | `Accepted`                               |
| A valid response, rejected transiently      | `isFailureTransient`                  | Retry                                    |
| A valid response, rejected permanently      | `isFailureTransient` is false         | `Dead`                                   |
| An invalid response, rejected transiently   | `isRejectedByInvalidResponse`, `isFailureByInvalidResponseTransient` | Retry     |
| An invalid response, rejected permanently   | `isRejectedByInvalidResponse`, `isFailureByInvalidResponseTransient` is false | `Dead` |
| An invalid response, not a rejection        | `isRejectedByInvalidResponse` is false | Retry                                   |
| Connection failed or dropped, or no response before the inflight timeout | | Retry                               |

A retry asks `isAttemptAvailable` with a `RetryContext` of when the request was dispatched, the time now and the number
of the attempt. If an attempt is available, `backoffAlgorithm` gives the delay until the request is sent again, by a
delayed trigger; otherwise the request is `Dead`. The redispatched request is the dispatched one, or what the
`repeatMessageCreator` makes of it, e.g. with a new timestamp. Like the first attempt, every redispatched attempt has
the inflight timeout.

### Redispatching an accepted or dead request

An application can send a request which is `Dead` again, e.g. from a backoffice function handling the requests given up
once the cause is fixed, or an accepted request, e.g. a simulator of a partner system repeating a callback to test how
the receiver handles a duplicate. The request is redispatched as by a retry, and is `InFlight` again: it is sent as the
`repeatMessageCreator` makes it of the dispatched request, so that the receiver gets what it would get from a retry,
such as the variant of a message a protocol expects when it is sent again. Without a `repeatMessageCreator`, it is the
dispatched request as it is stored.

A transition of the process that dispatched the request triggers `requestRedispatched()` of the dispatcher on the
request, selected by `HttpOutboxRequest.dispatchedBy(processId)`, the request of the outbox the process dispatched last:

```java
onEvent(CallbackRepeatRequested).to(Done)
    .assemble(c -> c.entityId())
    .trigger(callbacks.requestRedispatched())
    .on(callbacks.outbox())
    .identifiedBy(HttpOutboxRequest::dispatchedBy)
    .output()
```

The request is sent with the method, URI, headers and body of the message the `repeatMessageCreator` makes, and the
headers the forwarder adds when it sends a request. From then on, it is like any other attempt:

* It has the inflight timeout, and is retried as the predicates of the outbox decide. A retry asks `isAttemptAvailable`
  with the `RetryContext` of the request, which counts from when it was dispatched and the attempts before, so a
  request redispatched long after it was dispatched may have no attempt available.
* If it is accepted, it is `Accepted`, and the success callback tells the process, unless the request was accepted
  before, as the process has been told already. The response of the request, `response(log)`, is the one it was first
  accepted with.
* If the receiver rejects it permanently, e.g. as a duplicate, or its attempts run out, it is `Dead`, with the
  severity `Error`, also if it was accepted before.

A request which waits for a retry is sent right away by a redispatch on demand, and the scheduled retry is dropped. A
request which is in flight rejects it.

### Example

A notification to a merchant, which the merchant deduplicates by its id, retried every 30 seconds for a day:

```java
AtLeastOnce<Notification> notification = HttpOutboxRequest.atLeastOnce()
    .requestPayloadType(DataType.json(Notification.class))
    .responsePayloadType(DataType.json(Receipt.class))
    .forwarder(httpClient)
    .inflightTimeout(Duration.ofSeconds(5))
    .contentParser(JsonResponseValidator.json(Receipt.class))
    .isAccepted(_ -> true)
    .isFailureTransient(response -> response.statusCode() >= 500)
    .isRejectedByInvalidResponse(d -> d.t1().statusCode() >= 400)
    .isFailureByInvalidResponseTransient(d -> d.t1().statusCode() >= 500)
    .isAttemptAvailable(retry -> retry.now().isBefore(retry.enqueueTime().plusDays(1)))
    .backoffAlgorithm(_ -> Duration.ofSeconds(30))
    .build("MerchantNotification", notificationId);
```

The diagram generated from it shows every event, including the intermediate ones of the choices above:

<object data="../images/com.github.thxmasj.backend.http.outbox.MerchantNotification.svg" type="image/svg+xml"></object>

[Open the MerchantNotification diagram in full size](images/com.github.thxmasj.backend.http.outbox.MerchantNotification.svg)

## AtMostOnce

An `AtMostOnce` request is sent once. If its outcome is unknown, it stays unknown, and the application is told so:

```mermaid
stateDiagram-v2
    [*] --> InFlight: Request dispatched
    InFlight --> Accepted: Response, accepted
    InFlight --> Failed: Connection failed
    InFlight --> Failed: Response, rejected
    InFlight --> Unknown: Connection dropped or timeout
    InFlight --> Unknown: Response, neither accepted nor rejected
    InFlight --> RolledBack: Rollback dispatched
    Accepted --> RolledBack: Rollback dispatched
    Unknown --> RolledBack: Rollback dispatched
    Failed --> [*]
    Accepted --> [*]
    Unknown --> [*]
    RolledBack --> [*]
```

| State        | Meaning                                                                                                   |
|--------------|-----------------------------------------------------------------------------------------------------------|
| `InFlight`   | The request is sent, or about to be, and waits for its response                                           |
| `Accepted`   | The receiver accepted the request                                                                         |
| `Failed`     | The request was not handled: the connection failed before it was sent, or the receiver rejected it      |
| `Unknown`    | The request may or may not have been handled: no response came, or the response doesn't tell             |
| `RolledBack` | A rollback request is dispatched, to undo the request if it was handled                                   |

A connection that fails is safe to treat as a failure, since the request never reached the receiver. A connection
that drops, or a response that doesn't come before the inflight timeout, is not: the receiver may have handled it.

Every outcome has a callback, an event triggered on the entity of the process which dispatched the request:

| Outcome                                    | Decided by                            | State      | Callback                   | Default             |
|--------------------------------------------|---------------------------------------|------------|----------------------------|---------------------|
| Connection failed                          |                                       | `Failed`   | `onPeerUnavailable`        | `ServiceFoundUnavailable` |
| Connection dropped, or the timeout expired |                                       | `Unknown`  | `onMissingResponse`        | `TimeoutExpired`    |
| A valid response, accepted                 | `isAccepted`                          | `Accepted` | `onSuccess`                | None                |
| A valid response, rejected                 | `isRejected`                          | `Failed`   | `onFailure`                | None                |
| A valid response, neither                  |                                       | `Unknown`  | `onValidResponseUnknown`   | None                |
| An invalid response, rejected              | `isRejectedByInvalidResponse`         | `Failed`   | `onInvalidResponseRejection` | `InvalidResponseReceived` |
| An invalid response, not a rejection       |                                       | `Unknown`  | `onInvalidResponseUnknown` | `InvalidResponseReceived`   |

The events of an `AtMostOnce` request have no [severity](monitoring.md#severity) of their own. The process is told of
the outcomes by the callbacks, and its transitions on them declare the severity which fits, e.g. a warning for an
authorisation whose outcome is unknown.

### Rollback

An `AtMostOnce` request can have two rollback models, both `AtLeastOnce` outboxes: one for rolling back a request that
was accepted, and one for rolling back a request whose outcome is unknown, e.g. a reversal of a card authorisation.
The process rolls a request back by triggering `Rollback dispatched` on it, which moves it to `RolledBack` and
dispatches a request of the rollback model as a new entity. A request still `InFlight` is rolled back as one whose
outcome is unknown; its response, if it comes, is then ignored.

The rollback request message is created from the data `Rollback dispatched` is triggered with and the original
request, by the rollback message creator of the dispatcher (see [dispatching](#dispatching-a-request)). Since the
rollback model is `AtLeastOnce`, the receiver must tolerate a duplicate rollback, as well as a rollback of a request it
never received.

### Example

An authorisation of a card payment, reversed by an `AtLeastOnce` reversal if it is accepted or its outcome is unknown:

```java
AtMostOnce<AuthorisationRequest, ReversalRequest> authorisation = HttpOutboxRequest.atMostOnce()
    .requestPayloadType(DataType.json(AuthorisationRequest.class))
    .responsePayloadType(DataType.json(AcquirerResponse.class))
    .forwarder(httpClient)
    .processModel(Payment)
    .onMissingResponse(AuthorisationUnknown)
    .contentParser(JsonResponseValidator.json(AcquirerResponse.class))
    .onSuccess(AcquirerResponded, TypedHttpResponse::payload)
    .onFailure(AcquirerDeclined, TypedHttpResponse::payload)
    .isAccepted(response -> response.payload().approved())
    .isRejected(response -> !response.payload().approved())
    .isRejectedByInvalidResponse(_ -> false)
    .rollbackOnAcceptedModel(reversal)
    .rollbackOnUnknownModel(reversal)
    .build("Authorisation", authorisationId);
```

The diagram generated from it:

<object data="../images/com.github.thxmasj.backend.http.outbox.Authorisation.svg" type="image/svg+xml"></object>

[Open the Authorisation diagram in full size](images/com.github.thxmasj.backend.http.outbox.Authorisation.svg)

## Dispatching a request

An outbox request doesn't create its request messages, so it can be used by any process. A dispatcher pairs it with
the creator of its request message, from data of the transition dispatching it:

| Dispatcher                                | Outbox                    | Input of the request                        |
|-------------------------------------------|---------------------------|---------------------------------------------|
| `AtLeastOnceDispatcher`                   | `AtLeastOnce`             | The request message                         |
| `AtMostOnceDispatcher`                    | `AtMostOnce`, no rollback | The request message                         |
| `AtMostOnceWithRollbackDispatcher`        | `AtMostOnce`, rollback    | The request message                         |
| `BatchedAtLeastOnceDispatcher`            | `AtLeastOnce`             | The request message and the id of its batch |
| `BatchedAtMostOnceDispatcher`             | `AtMostOnce`, no rollback | The request message and the id of its batch |
| `BatchedAtMostOnceWithRollbackDispatcher` | `AtMostOnce`, rollback    | The request message and the id of its batch |

`of(...)` takes a function creating the request message from the data, and `reactive(...)` one creating it
reactively, from the data and the `DispatchContext` of the dispatching transition. A dispatcher with rollback also
takes the rollback message creator. The rollback message creator is part of the transitions of the outbox request, so
the dispatcher's `outbox()` is a copy of the outbox request with it (see `AtMostOnce.withRollback(..)`), with the same
name and id. Requests are triggered on the dispatcher's `outbox()`, and it is the one given to the state machine.

```java
var authorisations = AtMostOnceWithRollbackDispatcher.of(
    authorisation,
    (Authorisation a) -> TypedHttpRequest.create(POST, acquirerUri, new AuthorisationRequest(a)),
    (context, d) -> Mono.just(TypedHttpRequest.create(POST, reversalUri, new ReversalRequest(d.t2().payload())))
);
```

The transition dispatching the request triggers `requestDispatched()` of the dispatcher on a new entity of its outbox:

```java
onEvent(Authorise).to(Authorising)
    .assemble(c -> tuple(c.input(), DispatchContext.of(c)))
    .trigger(authorisations.requestDispatched())
    .withReactive(d -> authorisations.input(d.t2(), d.t1()))
    .on(authorisations.outbox())
    .identifiedBy(newEntityId())
    .output()
```

A process rolling the request back triggers `rollbackDispatched()` of the dispatcher on the entity of the request,
e.g. by the entity id it got back when it was dispatched.

The dispatcher has the entity models to give the state machine along with those of the application: the outbox
request, its rollback models and `HttpOutboxRequest.Index`, which the requests are indexed in (see
[below](#finding-the-requests-of-a-process)). The index is shared by all outbox requests, and an entity model given
more than once is run once:

```java
entityModels.addAll(authorisations.entityModels());
```

The outbox request is built with its process model, `processModel(Payment)`, whose transitions in turn trigger the
request on the dispatcher's outbox. A process model declared as a constant with `EntityModel.of(name, id, transitions)`
takes care of that, as its transitions are created when the state machine first asks for them, after the outbox
requests are built. A trigger on the outbox request the dispatcher was created with, rather than on its `outbox()`, is
found when the state machine is created, which fails with a message naming the trigger.

## Finding the requests of a process

Every request is indexed by the id of the entity which dispatched it, `ProcessReferenced`, and a batched request also
by the id of its batch, `BatchReferenced`. A process can thereby find its requests, select the last one it dispatched to
an outbox by `HttpOutboxRequest.dispatchedBy(processId)`, and wait for all the requests of a
batch to complete by a conditional trigger on `HttpOutboxRequest.requestAccepted()`, which is the same event type for
every outbox.
