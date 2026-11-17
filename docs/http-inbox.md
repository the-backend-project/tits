# HTTP inboxes

An inbox is the set of requests an application receives. Each type of request is an `HttpInboxRequest`: an entity
model whose entities are the requests of that type, with a body of one type, which trigger one event type on one entity
model of the application. Like an [outbox](http-outbox.md) request, every request has a state and an event log of its
own, drawn in the same kind of state diagram as the entities of the application (see the
[state diagrams](#state-diagrams)).

```java
HttpInboxRequest<Order> PlaceOrder = HttpInboxRequest
    .matching(m -> m.method() == Method.PUT && m.uri().getPath().startsWith("/orders/"))
    .contentParser(HttpInbox.jsonParser(Order.class))
    .deduplicateOn(parseMessageId("PUT /orders/(.*)", 1))
    .authorizer(HttpInbox.bearerToken(verifier, "orders"))
    .process(OrderPlaced, Orders, RoutedRequest::body, newEntity())
    .rollback(Rollback
        .matching(m -> m.method() == Method.DELETE)
        .noContent()
        .deduplicateOn(parseMessageId("DELETE /orders/(.*)", 1))
        .authorizer(HttpInbox.bearerToken(verifier, "orders")))
    .build("PlaceOrder", UUID.fromString("..."));
```

| Setting           | Required | What it does                                                                                     |
|-------------------|----------|--------------------------------------------------------------------------------------------------|
| `matching`        | Yes      | Tells which requests are of this type, e.g. by method and path                                    |
| `contentParser`   | Yes      | Parses the body, or `noContent()` to ignore it. An invalid body is answered with 400              |
| `process`         | Yes      | The event type the request triggers, the entity model and the entity, e.g. `newEntity()` or `parseEntityId(...)`, and the event's data, which an event without data leaves out. Repeated with a content predicate for [content routing](#content-routing) |
| `deduplicateOn`   | No       | The id of the request, by which a retried request is recognised. Without it, nothing is deduplicated, and the request can't be rolled back |
| `authorizer`      | No       | Authorizes the request, e.g. with a bearer token (see [access tokens](#access-tokens))           |
| `response`        | No       | A response of the application of its own, made from a `ResponseEventType` the application triggers |
| `normalizer`      | No       | Normalizes a request before it is compared with an earlier request with the same message id       |
| `rollback`        | No       | Requests rolling back a request by its message id, with an authorizer of their own if the request has one. Needs `deduplicateOn`, see [rollback](#rollback) |

`HttpInbox.transitions(requests, application)` gives the transitions to create the state machine with: those of the
application, and of the inbox requests, `RequestRouting` and `MessageIds`.

An inbox request declares the custom responses its own processes give, and no others. `HttpInbox.transitions` checks
this per request. It starts from the event of each process on its entity model, and follows:

- the transitions taken on that event, in any state, with every transition they may choose, as which one is taken is
  decided at runtime;
- the events those transitions trigger at once on other entities, which are calculated in the same session, followed
  in the same way.

The event types triggered on `HttpInbox.Request` along the way are the responses the request may get. Each must be
`RequestCompleted`, `InvalidRequestCompleted`, `FailedRequestCompleted` or one the request has a `response` for.
Otherwise the check fails, naming the request, the response and the event giving it, rather than leaving the response
to be rejected when it is given. A request on an entity model shared with other requests only declares the responses
its own events lead to.

Delayed triggers (`after(..)`), conditional triggers, reversals and actions such as sending an outbox request are not
followed, as what they lead to is calculated later. A response given that way, e.g. by the callback of an outbox
request, is not checked in advance. It is rejected when it is given, if the request doesn't take it.

The `HttpInboxListener` receives HTTP requests with reactor-netty and passes them to the inbox. A `RequestRouting` entity
is created for every request, and routes it to a new entity of the first inbox request matching it. A request no inbox
request matches is answered with 400 Bad Request. The inbox request validates the request: its body, message id,
authorization and the entity it selects. If it is valid, the inbox request triggers the event on the application's
entity and waits for the application to respond.

The application responds by triggering an event of a `ResponseEventType` on the inbox request, e.g. `RequestCompleted`,
which responds 201 Created, `InvalidRequestCompleted` (400) or `FailedRequestCompleted` (500), or one the inbox request
has a `response` for. It doesn't know which inbox request triggered it, since several can trigger the same event, so
it triggers the response on `HttpInbox.Request`, the group of all inbox requests, identified by the session. The state
machine takes it to the inbox request of the session:

```java
.trigger(RequestCompleted).with(d -> tuple("Order placed", d)).on(HttpInbox.Request).identifiedBy(entityIdFromSession())
```

The response goes back to the `RequestRouting` entity, which the listener sends to the client.

Requests carrying a message id are deduplicated through the `MessageIds` id entity model: a retried request gets the
response of the first one, also if the application rejected it, without triggering anything again. A message id is
the client's: the authorized subject and the id, or the id alone without an authorizer. All inbox requests share
`MessageIds`, so a client can use a message id once, for one request of one type. A request with the message id of an
earlier request it differs from, of the same or another inbox request, is answered with 400 Bad Request and the detail
`Conflict`, and so is a request with the message id of a request still being dispatched. The requests of an inbox
request without `deduplicateOn` are not deduplicated: each one is dispatched to the application, also when it
repeats an earlier one. Filters on the listener can authenticate, log or rewrite requests before they reach the inbox.

A response of 500 Internal Server Error, which the application triggers with `FailedRequestCompleted`, is an event of
[severity](monitoring.md#severity) `Error`.

If a function the inbox request is given throws an exception, e.g. its content parser, or its normalizer when a
retried request is compared with the first one, the transition fails and nothing of it is stored. The listener answers
the request with 500 Internal Server Error, and the state machine tells its `Listener` of the
[failed transition](monitoring.md#the-lines), which `JsonLinesListener` writes as an error. The same goes for a
transition of the application the request triggers. A request the client got wrong is better answered with an invalid
`Validated` or a refused `Authorization`, which give 4xx and the reason.

## Content routing

The requests to one endpoint can trigger different events, by their content. An inbox request then has several
processes, each chosen by a predicate on the parsed body, given with `when(description, predicate)`. The body is parsed
once, the processes are tried in the order they are given, and the first one whose predicate matches is taken. A
request matching none is answered with 400 Bad Request and the detail `No process for the content`. A process without
a predicate takes any content, so it can only be the last one, as a default.

Each process has its own event type, entity model, input and entity selector, while `deduplicateOn`, the
authorizer and the normalizer are those of the endpoint. Here the result of an enrolment activates the payment token
it is about, or rejects it, by its status:

```java
HttpInboxRequest<EnrolmentResult> EnrolmentResultReceived = HttpInboxRequest
    .matching(m -> m.requestLine().matches("POST /enrolments"))
    .contentParser(HttpInbox.jsonParser(EnrolmentResult.class))
    .deduplicateOn((_, result) -> valid(result.messageId()))
    .process(
        when("status == Accepted", result -> result.status() == EnrolmentStatus.ACCEPTED),
        Activate, PaymentToken, RoutedRequest::body, (_, result) -> entityId(result.tokenReference())
    )
    .process(
        when("status == Rejected", result -> result.status() == EnrolmentStatus.REJECTED),
        Reject, PaymentToken, (_, result) -> entityId(result.tokenReference())
    )
    .build("EnrolmentResultReceived", UUID.fromString("..."));
```

The diagram of the inbox request shows the choice: a `Request dispatched [status == Accepted]` and a
`Request dispatched [status == Rejected]`, each triggering its event. A single process can have a predicate too, e.g.
to answer an authentication response with an error with 400 Bad Request.

## Rollback

An inbox request with a `rollback` takes the requests the rollback's matcher matches as rollbacks of the request with
the message id the rollback's `deduplicateOn` gives, e.g. a `DELETE` with the id of an earlier `PUT`. Only an inbox
request with `deduplicateOn` can have a rollback, as a request is found by its message id: `rollback` is a method of the
builder `deduplicateOn` returns. The rollback is built like the inbox request, starting with `Rollback.matching(...)`:

| Setting         | Required               | What it does                                                                                       |
|-----------------|------------------------|----------------------------------------------------------------------------------------------------|
| `matching`      | Yes                    | Tells which requests are rollbacks of a request of this type, e.g. by method and path               |
| `contentParser` | Yes                    | Parses the content of a rollback, or `noContent()` to ignore it. Invalid content is answered with 400 |
| `deduplicateOn` | Yes                    | The message id of the request rolled back, from e.g. the path or the content, the same one the request's `deduplicateOn` gives. An invalid one is answered with 400 |
| `authorizer`    | If the request has one | Authorizes the rollback                                                                            |
| `normalizer`    | No                     | Normalizes a rollback before it is compared with the first rollback of the request                 |

A rollback goes to the entity of that request, which rolls back the events the request caused on the application and
responds 201 Created with `Rolled back`. A rollback of a request not received yet is answered with `Nothing to roll
back`, and takes the message id, so that the request is rejected with 422 Unprocessable Entity if it arrives later.

A rollback finds the request, and the request finds the events to roll back, through the event logs of the entities
involved. Here a `DELETE /orders/42` rolls back the `PUT /orders/42` of the `PlaceOrder` inbox request, whose process
placed an order. Each entity is a stack of its events, the oldest on top, under its entity id and entity model, with the
kind of entity as a stereotype. The entity of `MessageIds` has the same entity id, `p`, as the entity of the inbox
request. An event in italics, with a dashed border, leads to a choice and is not stored: only the event of the chosen
transition is. Solid arrows are triggers, and dashed arrows the event references:

<object data="../images/com.github.thxmasj.backend.http.inbox.Rollback.svg" type="image/svg+xml"></object>

[Open the rollback diagram in full size](images/com.github.thxmasj.backend.http.inbox.Rollback.svg)

1. Each HTTP request is received by a `RequestRouting` entity of its own, which routes it to its inbox request.
2. A valid request claims its message id, the subject and the id its `deduplicateOn` gives, on the `MessageIds` entity
   with the same entity id as the request's own entity, unless an earlier request or rollback has claimed it.
3. The process of the request triggers its event on the application, here `Order placed` as event `n` of the order.
   `Request accepted` keeps an `EventReference` to it: the entity id, `o`, and the event number. The application responds by
   triggering `Request completed`, which gives the response to the `RequestRouting` entity of the request.
4. A rollback is routed to the entity which claimed the message id its `deduplicateOn` gives, and there takes the
   `EventReference` of `Request accepted`. It triggers `Events rolled back` on the entity referred to, rolling it back
   to the event before the one referred to, `n − 1`. `Rollback request accepted` refers to that rollback event, and
   claims the message id for the rollback.

The events rolled back stay in the event log, but are no longer effective: the state of the order, and everything read
from its log, is as it was after event `n − 1`. This includes events after `n` which the request did not cause, so the
application decides, by its transitions on `Events rolled back`, in which states of the entity a rollback is accepted.

A request is rolled back once. A rollback repeated after the first one, which has the same message id, is compared with
the first one as a request is: equal after normalizing, it is a retry and gets the response of the first one in both
cases, and otherwise it is a second rollback, which is rejected with 400 Bad Request and `Conflict`. Without a
normalizer, a rollback is compared as it is, headers included, so a retry must be sent as it was. A normalizer which
removes e.g. the date and tracing headers lets a retry differ in them, and one which keeps e.g. `X-Correlation-Id` tells
a second rollback by it.

A rollback has content of its own, of a type of its own, which its `deduplicateOn` and `authorizer` read. The message
id can be in the content instead of the path, e.g. a reversal posted with the id of the order:

```java
.rollback(Rollback
    .matching(m -> m.requestLine().equals("POST /order-reversals"))
    .contentParser(HttpInbox.jsonParser(OrderReversal.class))
    .deduplicateOn((_, reversal) -> valid(reversal.orderId()))
    .authorizer(HttpInbox.bearerToken(verifier, "orders")))
```

As the content of a rollback isn't the request's, the authorizer of the request isn't used for it. A request with an
authorizer takes a rollback with an authorizer of its own, and a request without one a rollback without one. The
message id of a request is the authorized subject's, so the rollback is of the request the same subject sent: another
subject's rollback with the message id has nothing to roll back. The message ids of all inbox requests share
`MessageIds`, but a rollback only finds requests of its own inbox request: the message id of another type of request
has nothing to roll back. The processes of an inbox request with a rollback must have the same entity model, which the
rollback rolls the events back on.

## Access tokens

Inbox requests requiring an access token, e.g. one issued by the [identity provider](identity-provider.md), take
`HttpInbox.bearerToken(verifier, scopes...)` as their authorizer, which authorizes the `sub` of the bearer token if the
verifier finds it valid and it has the scopes. The `AccessTokenVerifier` is given the keys of the issuer, its issuer
URI and optionally the audience the tokens must have:

```java
var verifier = AccessTokenVerifier.fetchingKeys(
    URI.create("https://idp.example.com/.well-known/jwks.json"),
    URI.create("https://idp.example.com"),
    "payments-api",
    Clock.systemUTC()
);
var authorizer = HttpInbox.bearerToken(verifier, "payments");
```

The verifier accepts JWT access tokens as profiled by [RFC 9068](https://www.rfc-editor.org/rfc/rfc9068): of type
`at+jwt`, signed with RS256 by a key of the issuer, of the issuer, not expired, for the audience if one is given, and
with the claims `sub` and `client_id`. A refused request is answered as
[RFC 6750](https://www.rfc-editor.org/rfc/rfc6750#section-3) says, with a `WWW-Authenticate` header: 401 and the
challenge `Bearer` without a bearer token, 401 and the error `invalid_token` with a token that is invalid or expired,
and 403 and the error `insufficient_scope` with a token lacking a scope. An authorizer returns an `Authorization`,
either `Authorized` with the subject or `Refused` with the status code, challenge and reason of the response;
`Authorization.refused(reason)` refuses with 400. The keys are fetched once, so the verifier must be created again to see keys the
issuer has added since.

## State diagrams

`RequestRouting` routes a request to the inbox request matching it. The diagram is of an inbox with one inbox request,
`PlaceOrder`, which can be rolled back:

<object data="../images/com.github.thxmasj.backend.http.inbox.RequestRouting.svg" type="image/svg+xml"></object>

[Open the RequestRouting diagram in full size](images/com.github.thxmasj.backend.http.inbox.RequestRouting.svg)

The `PlaceOrder` inbox request, from receiving the request to its response, and its rollback:

<object data="../images/com.github.thxmasj.backend.http.inbox.HttpInboxRequest.svg" type="image/svg+xml"></object>

[Open the PlaceOrder diagram in full size](images/com.github.thxmasj.backend.http.inbox.HttpInboxRequest.svg)

| State         | Meaning                                                                                                   |
|---------------|-----------------------------------------------------------------------------------------------------------|
| `Dispatched`  | The request is dispatched to the application, which hasn't responded yet                                   |
| `Rejected`    | The application rejected the request, or the message id is taken by another or a rolled back request       |
| `Completed`   | The request is answered, also if it was invalid or refused                                                 |
| `RollingBack` | A rollback of the request is received, and its events on the application are being rolled back             |
| `RolledBack`  | The request is rolled back                                                                                 |
| `RejectedRollbackFromDispatched`, `RejectedRollbackFromCompleted` | The application rejected the rollback                  |

The rollback states are only there for an inbox request with a `rollback`.
