package com.github.thxmasj.backend.http.outbox;

import static com.github.thxmasj.backend.core.EntitySelector.entityId;
import static com.github.thxmasj.backend.core.EntitySelector.entityIdFromSession;
import static com.github.thxmasj.backend.core.EntitySelector.newEntityId;
import static com.github.thxmasj.backend.core.TransitionModelBuilder.WithEvent.onEvent;
import static com.github.thxmasj.backend.core.Tuples.tuple;
import static com.github.thxmasj.backend.http.outbox.AtMostOnce.States.Accepted;
import static com.github.thxmasj.backend.http.outbox.AtMostOnce.States.Failed;
import static com.github.thxmasj.backend.http.outbox.AtMostOnce.States.InFlight;
import static com.github.thxmasj.backend.http.outbox.AtMostOnce.States.Intermediate;
import static com.github.thxmasj.backend.http.outbox.AtMostOnce.States.RolledBack;
import static com.github.thxmasj.backend.http.outbox.AtMostOnce.States.Unknown;
import static com.github.thxmasj.backend.http.outbox.EventTypes.ConnectionDropped;
import static com.github.thxmasj.backend.http.outbox.EventTypes.ConnectionFailed;
import static com.github.thxmasj.backend.http.outbox.EventTypes.ResponseReceived;
import static com.github.thxmasj.backend.http.outbox.EventTypes.TimeoutExpired;
import static com.github.thxmasj.backend.http.outbox.HttpOutboxRequest.toHttpRequest;

import com.github.thxmasj.backend.core.BasicEventType;
import com.github.thxmasj.backend.core.DataType;
import com.github.thxmasj.backend.core.EntityModel;
import com.github.thxmasj.backend.core.EventLog;
import com.github.thxmasj.backend.core.EventType;
import com.github.thxmasj.backend.core.State;
import com.github.thxmasj.backend.core.TransitionModel;
import com.github.thxmasj.backend.core.TransitionModelBuilder.TransitionContext;
import com.github.thxmasj.backend.core.Tuples.Tuple2;
import com.github.thxmasj.backend.core.Validated;
import com.github.thxmasj.backend.http.HttpClient;
import com.github.thxmasj.backend.http.HttpDataType;
import com.github.thxmasj.backend.core.internal.Memoized;
import com.github.thxmasj.backend.http.HttpRequest;
import com.github.thxmasj.backend.http.HttpResponse;
import com.github.thxmasj.backend.http.Masking;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import reactor.core.publisher.Mono;

/// An outbox request which is dispatched at most once, with request payloads of type `RQ`. It can be rolled back by
/// rollback models with request payloads of type `RRQ`. The rollback request message is created by a rollback message
/// creator, from the data the rollback is dispatched with and the request being rolled back. Like the creator of the
/// request message, it is kept outside the outbox request; it is given to [#withRollback(BiFunction)] or
/// [#withBatchedRollback(BiFunction)], e.g. by a [Dispatcher], which give a copy of the outbox request with the
/// transitions rolling back with it.
public final class AtMostOnce<RQ, RRQ> implements HttpOutboxRequest<RQ> {

  private final String name;
  private final UUID id;
  private final AtLeastOnce<RRQ> rollbackOnAcceptedModel;
  private final AtLeastOnce<RRQ> rollbackOnUnknownModel;
  private final Shape<?, ?, ?, ?, ?, ?, ?, RQ, ?> shape;
  /// The creators of the rollback models, given this, which a copy creates its own rollback models with
  private final Function<AtMostOnce<RQ, RRQ>, AtLeastOnce<RRQ>> rollbackOnAcceptedModelSupplier;
  private final Function<AtMostOnce<RQ, RRQ>, AtLeastOnce<RRQ>> rollbackOnUnknownModelSupplier;
  private final Function<AtMostOnce<RQ, RRQ>, Map<State, List<TransitionModel<?, ?>>>> transitionsCreator;
  /// The transitions, which are kept once the rollback models they trigger on are created, and so null until then
  private final Memoized<Map<State, List<TransitionModel<?, ?>>>> transitions;
  /// The copies with rollback message creators, see [#withRollback(BiFunction)]
  private final Map<CopyKey, AtMostOnce<RQ, RRQ>> copies = new HashMap<>();

  private record Shape<T1, T2, T3, T4, T5, T6, T7, RQ, RS>(
      DataType<RQ> requestPayloadType,
      DataType<RS> responsePayloadType,
      ForwardAction forward,
      Duration inflightTimeout,
      com.github.thxmasj.backend.core.EntityModel processModel,
      Callback<EntityModel, T1> onPeerUnavailable,
      Callback<EntityModel, T2> onMissingResponse,
      Callback<HttpResponse<RS>, T3> onSuccess,
      Callback<HttpResponse<RS>, T4> onFailure,
      Callback<HttpResponse<RS>, T7> onValidResponseUnknown, // Rollback
      Callback<Tuple2<HttpResponse<byte[]>, String>, T5> onInvalidResponseRejection,
      Callback<Tuple2<HttpResponse<byte[]>, String>, T6> onInvalidResponseUnknown, // Rollback
      Function<HttpResponse<byte[]>, Validated<RS>> contentParser,
      BiFunction<HttpRequest<RQ>, HttpResponse<byte[]>, Validated<RS>> requestAwareContentParser,
      Predicate<HttpResponse<RS>> isAccepted,
      Predicate<HttpResponse<RS>> isRejected,
      Predicate<Tuple2<HttpResponse<byte[]>, String>> isRejectedByInvalidResponse,
      Masking masking
  ) {

    /// The request as stored, masked as bytes. A body which can't be read as the payload once masked is stored as none.
    HttpRequest<RQ> maskedRequest(HttpRequest<RQ> request) {
      HttpRequest<byte[]> raw = request.map(body -> body != null ? requestPayloadType.marshal(body) : null);
      HttpRequest<byte[]> masked = masking.mask(raw);
      return masked.equals(raw) ? request : masked.map(body -> payload(requestPayloadType, body));
    }

    /// The response as stored, masked as bytes. A body which can't be read as the payload once masked, e.g. a masked
    /// JSON body, is stored as none.
    HttpResponse<RS> maskedResponse(HttpResponse<RS> response) {
      HttpResponse<byte[]> raw = response.map(body -> body != null ? responsePayloadType.marshal(body) : null);
      HttpResponse<byte[]> masked = masking.mask(raw);
      return masked.equals(raw) ? response : masked.map(body -> payload(responsePayloadType, body));
    }

    private static <P> P payload(DataType<P> payloadType, byte[] body) {
      if (body == null)
        return null;
      try {
        return payloadType.unmarshal(body);
      } catch (RuntimeException e) {
        return null;
      }
    }

    EventType<HttpRequest<RQ>, Tuple2<HttpRequest<RQ>, UUID>> requestDispatched() {
      return BasicEventType.of(
          "Request dispatched",
          UUID.fromString("3f22bfef-dcb4-4560-8b88-6b5040433319"),
          HttpDataType.forRequest(requestPayloadType),
          DataType.tuple(HttpDataType.forRequest(requestPayloadType), DataType.uuid())
      );
    }

    EventType<BatchedRequest<RQ>, Tuple2<HttpRequest<RQ>, UUID>> batchedRequestDispatched() {
      return BasicEventType.of(
          "Batched request dispatched",
          UUID.fromString("6c2e8f4b-1a7d-4f3e-b5c9-8e0d2a4f6b13"),
          BatchedRequest.dataType(requestPayloadType),
          DataType.tuple(HttpDataType.forRequest(requestPayloadType), DataType.uuid())
      );
    }

    /// The request message and the process id of the dispatched request, whether batched or not.
    Tuple2<HttpRequest<RQ>, UUID> dispatched(EventLog log) {
      return log.one(requestDispatched(), batchedRequestDispatched());
    }
    // Intermediate
    EventType<HttpResponse<RS>, HttpResponse<RS>> validResponse() {
      return BasicEventType.of(
          "Valid response received",
          UUID.fromString("c9fcf4d6-95f8-418f-aa6b-c3d987d3a3c3"),
          HttpDataType.forResponse(responsePayloadType),
          HttpDataType.forResponse(responsePayloadType)
      );
    }
    // Intermediate
    EventType<Tuple2<HttpResponse<byte[]>, String>, Tuple2<HttpResponse<byte[]>, String>> invalidResponse() {
      return BasicEventType.of(
          "Invalid response received",
          UUID.fromString("3bd020c3-caf8-4a9b-a10c-d818f98a6de7"),
          DataType.tuple(HttpDataType.forResponse(), DataType.string()),
          DataType.tuple(HttpDataType.forResponse(), DataType.string())
      );
    }
    // Leaf
    EventType<HttpResponse<RS>, HttpResponse<RS>> requestAccepted() {
      return BasicEventType.of(
          "Request accepted",
          RequestAcceptedId,
          HttpDataType.forResponse(responsePayloadType),
          HttpDataType.forResponse(responsePayloadType)
      );
    }
    // Leaf
    EventType<HttpResponse<RS>, HttpResponse<RS>> validResponseAndRejected() {
      return BasicEventType.of(
          "Request rejected",
          UUID.fromString("5362567f-792e-4f8a-81d6-3b201d44d3f0"),
          HttpDataType.forResponse(responsePayloadType),
          HttpDataType.forResponse(responsePayloadType)
      );
    }
    // Leaf
    EventType<HttpResponse<RS>, HttpResponse<RS>> validResponseAndUnknown() {
      return BasicEventType.of(
          "Valid response received with unknown status",
          UUID.fromString("0059d0cc-e7ec-4f84-beb0-1870d00937d2"),
          HttpDataType.forResponse(responsePayloadType),
          HttpDataType.forResponse(responsePayloadType)
      );
    }
    // Leaf
    EventType<Tuple2<HttpResponse<byte[]>, String>, HttpResponse<byte[]>> invalidResponseAndRejected() {
      return BasicEventType.of(
          "Request rejected",
          UUID.fromString("402f9bf4-855c-4383-ac54-3375f9d156d1"),
          DataType.tuple(HttpDataType.forResponse(), DataType.string()),
          HttpDataType.forResponse()
      );
    }
    // Leaf
    EventType<Tuple2<HttpResponse<byte[]>, String>, HttpResponse<byte[]>> invalidResponseAndUnknown() {
      return BasicEventType.of(
          "Invalid response received with unknown status",
          UUID.fromString("ecfb2c9d-178b-4c3e-b09e-c580e09b01b4"),
          DataType.tuple(HttpDataType.forResponse(), DataType.string()),
          HttpDataType.forResponse()
      );
    }

  }

  @Override
  public EventType<HttpRequest<RQ>, ?> requestDispatched() {
    return shape.requestDispatched();
  }

  @Override
  public EventType<BatchedRequest<RQ>, ?> batchedRequestDispatched() {
    return shape.batchedRequestDispatched();
  }

  /// Rolls this request back with data of type `RI`. The rollback request is dispatched by the rollback on accepted
  /// model if this request was accepted, and by the rollback on unknown model if its outcome is unknown.
  public <RI> EventType<RI, Void> rollbackDispatched() {
    return BasicEventType.of(
        "Rollback dispatched",
        RollbackDispatchedId,
        DataType.unknown(),
        DataType.none()
    );
  }

  @Override
  public DataType<RQ> requestPayloadType() {
    return shape.requestPayloadType();
  }

  @Override
  public DataType<?> responsePayloadType() {
    return shape.responsePayloadType();
  }

  @Override
  public HttpRequest<RQ> request(EventLog log) {
    return shape.dispatched(log).t1();
  }

  @Override
  public HttpResponse<?> response(EventLog log) {
    return response(shape, log);
  }

  private <RS> HttpResponse<RS> response(Shape<?, ?, ?, ?, ?, ?, ?, ?, RS> shape, EventLog log) {
    return log.oneIfExists(shape.requestAccepted())
        .orElse(log.oneIfExists(shape.validResponseAndRejected())
            .orElse(log.oneIfExists(shape.validResponseAndUnknown()).orElse(null)));
  }

  public AtLeastOnce<RRQ> rollbackOnAcceptedModel() {
    return rollbackOnAcceptedModel;
  }

  public AtLeastOnce<RRQ> rollbackOnUnknownModel() {
    return rollbackOnUnknownModel;
  }

  public <T1, T2, T3, T4, T5, T6, T7, RS> AtMostOnce(
      String name,
      UUID id,
      DataType<RQ> requestPayloadType,
      DataType<RS> responsePayloadType,
      HttpClient forwarder,
      Duration inflightTimeout,
      com.github.thxmasj.backend.core.EntityModel processModel,
      Callback<EntityModel, T1> onPeerUnavailable,
      Callback<EntityModel, T2> onMissingResponse,
      Callback<HttpResponse<RS>, T3> onSuccess,
      Callback<HttpResponse<RS>, T4> onFailure,
      Callback<HttpResponse<RS>, T7> onValidResponseUnknown, // Rollback
      Callback<Tuple2<HttpResponse<byte[]>, String>, T5> onInvalidResponseRejection,
      Callback<Tuple2<HttpResponse<byte[]>, String>, T6> onInvalidResponseUnknown, // Rollback
      Function<HttpResponse<byte[]>, Validated<RS>> contentParser,
      BiFunction<HttpRequest<RQ>, HttpResponse<byte[]>, Validated<RS>> requestAwareContentParser,
      Predicate<HttpResponse<RS>> isAccepted,
      Predicate<HttpResponse<RS>> isRejected,
      Predicate<Tuple2<HttpResponse<byte[]>, String>> isRejectedByInvalidResponse,
      AtLeastOnce<RRQ> rollbackOnAcceptedModel,
      AtLeastOnce<RRQ> rollbackOnUnknownModel
  ) {
    this(
        name, id, requestPayloadType, responsePayloadType, forwarder, inflightTimeout, processModel, onPeerUnavailable,
        onMissingResponse, onSuccess, onFailure, onValidResponseUnknown, onInvalidResponseRejection,
        onInvalidResponseUnknown, contentParser, requestAwareContentParser, isAccepted, isRejected,
        isRejectedByInvalidResponse, rollbackOnAcceptedModel, rollbackOnUnknownModel, Masking.CREDENTIALS
    );
  }

  /// @param masking what of the request and response is masked in the event log, see [Masking]
  public <T1, T2, T3, T4, T5, T6, T7, RS> AtMostOnce(
      String name,
      UUID id,
      DataType<RQ> requestPayloadType,
      DataType<RS> responsePayloadType,
      HttpClient forwarder,
      Duration inflightTimeout,
      com.github.thxmasj.backend.core.EntityModel processModel,
      Callback<EntityModel, T1> onPeerUnavailable,
      Callback<EntityModel, T2> onMissingResponse,
      Callback<HttpResponse<RS>, T3> onSuccess,
      Callback<HttpResponse<RS>, T4> onFailure,
      Callback<HttpResponse<RS>, T7> onValidResponseUnknown, // Rollback
      Callback<Tuple2<HttpResponse<byte[]>, String>, T5> onInvalidResponseRejection,
      Callback<Tuple2<HttpResponse<byte[]>, String>, T6> onInvalidResponseUnknown, // Rollback
      Function<HttpResponse<byte[]>, Validated<RS>> contentParser,
      BiFunction<HttpRequest<RQ>, HttpResponse<byte[]>, Validated<RS>> requestAwareContentParser,
      Predicate<HttpResponse<RS>> isAccepted,
      Predicate<HttpResponse<RS>> isRejected,
      Predicate<Tuple2<HttpResponse<byte[]>, String>> isRejectedByInvalidResponse,
      AtLeastOnce<RRQ> rollbackOnAcceptedModel,
      AtLeastOnce<RRQ> rollbackOnUnknownModel,
      Masking masking
  ) {
    this(
        name,
        id,
        requestPayloadType,
        responsePayloadType,
        forwarder,
        inflightTimeout,
        processModel,
        onPeerUnavailable,
        onMissingResponse,
        onSuccess,
        onFailure,
        onValidResponseUnknown,
        onInvalidResponseRejection,
        onInvalidResponseUnknown,
        contentParser,
        requestAwareContentParser,
        isAccepted,
        isRejected,
        isRejectedByInvalidResponse,
        _ -> rollbackOnAcceptedModel,
        _ -> rollbackOnUnknownModel,
        masking
    );
  }

  public <T1, T2, T3, T4, T5, T6, T7, RS> AtMostOnce(
      String name,
      UUID id,
      DataType<RQ> requestPayloadType,
      DataType<RS> responsePayloadType,
      HttpClient forwarder,
      Duration inflightTimeout,
      com.github.thxmasj.backend.core.EntityModel processModel,
      Callback<EntityModel, T1> onPeerUnavailable,
      Callback<EntityModel, T2> onMissingResponse,
      Callback<HttpResponse<RS>, T3> onSuccess,
      Callback<HttpResponse<RS>, T4> onFailure,
      Callback<HttpResponse<RS>, T7> onValidResponseUnknown, // Rollback
      Callback<Tuple2<HttpResponse<byte[]>, String>, T5> onInvalidResponseRejection,
      Callback<Tuple2<HttpResponse<byte[]>, String>, T6> onInvalidResponseUnknown, // Rollback
      Function<HttpResponse<byte[]>, Validated<RS>> contentParser,
      BiFunction<HttpRequest<RQ>, HttpResponse<byte[]>, Validated<RS>> requestAwareContentParser,
      Predicate<HttpResponse<RS>> isAccepted,
      Predicate<HttpResponse<RS>> isRejected,
      Predicate<Tuple2<HttpResponse<byte[]>, String>> isRejectedByInvalidResponse,
      Function<AtMostOnce<RQ, RRQ>, AtLeastOnce<RRQ>> rollbackOnAcceptedModelSupplier,
      Function<AtMostOnce<RQ, RRQ>, AtLeastOnce<RRQ>> rollbackOnUnknownModelSupplier
  ) {
    this(
        name, id, requestPayloadType, responsePayloadType, forwarder, inflightTimeout, processModel, onPeerUnavailable,
        onMissingResponse, onSuccess, onFailure, onValidResponseUnknown, onInvalidResponseRejection,
        onInvalidResponseUnknown, contentParser, requestAwareContentParser, isAccepted, isRejected,
        isRejectedByInvalidResponse, rollbackOnAcceptedModelSupplier, rollbackOnUnknownModelSupplier, Masking.CREDENTIALS
    );
  }

  /// @param masking what of the request and response is masked in the event log, besides [Masking#CREDENTIALS],
  ///                which every at-most-once request masks. The request is never sent again from the event log, so it
  ///                can be masked, but what reads it from the log, e.g. a request aware content parser in the session
  ///                of the response, or a rollback message creator, gets it masked.
  public <T1, T2, T3, T4, T5, T6, T7, RS> AtMostOnce(
      String name,
      UUID id,
      DataType<RQ> requestPayloadType,
      DataType<RS> responsePayloadType,
      HttpClient forwarder,
      Duration inflightTimeout,
      com.github.thxmasj.backend.core.EntityModel processModel,
      Callback<EntityModel, T1> onPeerUnavailable,
      Callback<EntityModel, T2> onMissingResponse,
      Callback<HttpResponse<RS>, T3> onSuccess,
      Callback<HttpResponse<RS>, T4> onFailure,
      Callback<HttpResponse<RS>, T7> onValidResponseUnknown, // Rollback
      Callback<Tuple2<HttpResponse<byte[]>, String>, T5> onInvalidResponseRejection,
      Callback<Tuple2<HttpResponse<byte[]>, String>, T6> onInvalidResponseUnknown, // Rollback
      Function<HttpResponse<byte[]>, Validated<RS>> contentParser,
      BiFunction<HttpRequest<RQ>, HttpResponse<byte[]>, Validated<RS>> requestAwareContentParser,
      Predicate<HttpResponse<RS>> isAccepted,
      Predicate<HttpResponse<RS>> isRejected,
      Predicate<Tuple2<HttpResponse<byte[]>, String>> isRejectedByInvalidResponse,
      Function<AtMostOnce<RQ, RRQ>, AtLeastOnce<RRQ>> rollbackOnAcceptedModelSupplier,
      Function<AtMostOnce<RQ, RRQ>, AtLeastOnce<RRQ>> rollbackOnUnknownModelSupplier,
      Masking masking
  ) {
    this.name = name;
    this.id = id;
    this.transitionsCreator = outbox -> outbox.transitions(
        outbox.shape,
        AtLeastOnce::requestDispatched,
        (_, _) -> Mono.error(new IllegalStateException(
            name + " has no rollback message creator. Use the outbox request of its Dispatcher."
        ))
    );
    this.shape = new Shape<>(
        requestPayloadType,
        responsePayloadType,
        new ForwardAction(forwarder),
        inflightTimeout,
        processModel,
        onPeerUnavailable,
        onMissingResponse,
        onSuccess,
        onFailure,
        onValidResponseUnknown,
        onInvalidResponseRejection,
        onInvalidResponseUnknown,
        contentParser,
        requestAwareContentParser,
        isAccepted,
        isRejected,
        isRejectedByInvalidResponse,
        Masking.CREDENTIALS.and(masking)
    );
    this.rollbackOnAcceptedModelSupplier = rollbackOnAcceptedModelSupplier;
    this.rollbackOnUnknownModelSupplier = rollbackOnUnknownModelSupplier;
    this.rollbackOnAcceptedModel = rollbackOnAcceptedModelSupplier != null ? rollbackOnAcceptedModelSupplier.apply(this) : null;
    this.rollbackOnUnknownModel = rollbackOnUnknownModelSupplier != null ? rollbackOnUnknownModelSupplier.apply(this) : null;
    this.transitions = new Memoized<>(this::createTransitions, "transitions of " + name);
  }

  /// A copy of the outbox request, with the same name and id, whose transitions are created by the given creator. Its
  /// rollback models are created by the creators of the outbox request, given the copy, so that they are of the copy.
  private AtMostOnce(
      AtMostOnce<RQ, RRQ> outbox,
      Function<AtMostOnce<RQ, RRQ>, Map<State, List<TransitionModel<?, ?>>>> transitionsCreator
  ) {
    this.name = outbox.name;
    this.id = outbox.id;
    this.transitionsCreator = transitionsCreator;
    this.shape = outbox.shape;
    this.rollbackOnAcceptedModelSupplier = outbox.rollbackOnAcceptedModelSupplier;
    this.rollbackOnUnknownModelSupplier = outbox.rollbackOnUnknownModelSupplier;
    this.rollbackOnAcceptedModel = rollbackOnAcceptedModelSupplier != null ? rollbackOnAcceptedModelSupplier.apply(this) : null;
    this.rollbackOnUnknownModel = rollbackOnUnknownModelSupplier != null ? rollbackOnUnknownModelSupplier.apply(this) : null;
    this.transitions = new Memoized<>(this::createTransitions, "transitions of " + name);
  }

  private <RI, RRI, T1, T2, T3, T4, T5, T6, T7, RS> Map<State, List<TransitionModel<?, ?>>> transitions(
      Shape<T1, T2, T3, T4, T5, T6, T7, RQ, RS> shape,
      Function<AtLeastOnce<RRQ>, EventType<RRI, ?>> rollbackRequestDispatched,
      BiFunction<DispatchContext, Tuple2<RI, HttpRequest<RQ>>, Mono<RRI>> rollbackMessageCreator
  ) {
    return Map.of(
        Begin, List.of(
            onEvent(shape.requestDispatched()).to(InFlight).masked(d -> tuple(shape.maskedRequest(d.t1()), d.t2()))
                .assemble(c -> tuple(
                    c.input(),
                    c.triggerEvent(), // process id
                    c.entityId() // my id
                ))

                .trigger(ProcessReferenced)
                .with(d -> d.t2().entityId()) // process id
                .on(Index)
                .identifiedBy(d -> newEntityId(d.t3()))

                .trigger(shape.forward()).with(d -> toHttpRequest(d.t1().t1(), shape.requestPayloadType()))
                .trigger(TimeoutExpired).after(_ -> shape.inflightTimeout())
                .output(d -> tuple(d.t1().t1(), d.t1().t2().entityId())),
            onEvent(shape.batchedRequestDispatched()).to(InFlight).masked(d -> tuple(shape.maskedRequest(d.t1()), d.t2()))
                .assemble(c -> tuple(
                    c.input().request(),
                    c.triggerEvent(), // process id
                    c.entityId(), // my id
                    c.input().batchId()
                ))

                .trigger(ProcessReferenced)
                .with(d -> d.t2().entityId()) // process id
                .on(Index)
                .identifiedBy(d -> newEntityId(d.t3()))

                .trigger(BatchReferenced)
                .with(d -> d.t1().t4()) // batch id
                .on(Index)
                .identifiedBy(d -> entityId(d.t1().t3())) // NB: entity id created above

                .trigger(shape.forward()).with(d -> toHttpRequest(d.t1().t1().t1(), shape.requestPayloadType()))
                .trigger(TimeoutExpired).after(_ -> shape.inflightTimeout())
                .output(d -> tuple(d.t1().t1().t1(), d.t1().t1().t2().entityId()))
        ),
        InFlight, list(
            rollbackOnUnknownModel != null ?
                onEvent(this.<RI>rollbackDispatched()).to(RolledBack)
                    .assemble(c -> tuple(rollbackContext(c), tuple(c.input(), shape.dispatched(c.log()).t1())))
                    .trigger(rollbackRequestDispatched.apply(rollbackOnUnknownModel))
                    .withReactive(d -> rollbackMessageCreator.apply(d.t1(), d.t2()))
                    .on(rollbackOnUnknownModel)
                    .identifiedBy(newEntityId())
                    .output() : null,
            shape.onPeerUnavailable() != null ?
                onEvent(ConnectionFailed).to(Failed)
                    .assemble(c -> c.log().entityModel())
                    .trigger(shape.onPeerUnavailable().eventType())
                    .with(shape.onPeerUnavailable().dataAdapter())
                    .on(shape.processModel())
                    .identifiedBy(entityIdFromSession())
                    .output() :
                onEvent(ConnectionFailed).to(Failed).output(),
            shape.onMissingResponse() != null ?
                onEvent(ConnectionDropped).to(Unknown)
                    .assemble(c -> c.log().entityModel())
                    .trigger(shape.onMissingResponse().eventType())
                    .with(shape.onMissingResponse().dataAdapter())
                    .on(shape.processModel())
                    .identifiedBy(entityIdFromSession())
                    .output() :
                onEvent(ConnectionDropped).to(Unknown).output(),
            shape.onMissingResponse() != null ?
                onEvent(TimeoutExpired).to(Unknown)
                    .assemble(c -> tuple(c.log().entityModel(), shape.dispatched(c.log()).t2()))
                    .trigger(shape.onMissingResponse().eventType())
                    .with(d -> shape.onMissingResponse().dataAdapter().apply(d.t1()))
                    .on(shape.processModel())
                    .identifiedBy(d -> entityId(d.t2()))
                    .output() :
                onEvent(TimeoutExpired).to(Unknown).output(),
            onEvent(ResponseReceived).to(Intermediate)
                .assemble(c -> tuple(
                    c.input(),
                    shape.requestAwareContentParser() != null ?
                        shape.requestAwareContentParser().apply(
                            shape.dispatched(c.log()).t1(),
                            c.input()
                        ) :
                        shape.contentParser().apply(c.input())
                ))
                .when(d -> d.t2().isValid())
                .then(
                    onEvent(shape.validResponse()).to(Intermediate)
                        .assembleInput()
                        .when(shape.isAccepted())
                        .then(
                            shape.onSuccess() != null ?
                                onEvent(shape.requestAccepted()).to(Accepted).masked(shape::maskedResponse)
                                    .assembleInput()
                                    .trigger(shape.onSuccess().eventType())
                                    .with(shape.onSuccess().dataAdapter())
                                    .on(shape.processModel())
                                    .identifiedBy(entityIdFromSession())
                                    .output(d -> d.t1()) :
                                onEvent(shape.requestAccepted()).to(Accepted).masked(shape::maskedResponse)
                                    .assembleInput()
                                    .output(d -> d)
                        )
                        .when(shape.isRejected())
                        .then(
                            shape.onFailure() != null ?
                                onEvent(shape.validResponseAndRejected()).to(Failed).masked(shape::maskedResponse)
                                    .assembleInput()
                                    .trigger(shape.onFailure().eventType())
                                    .with(shape.onFailure().dataAdapter())
                                    .on(shape.processModel())
                                    .identifiedBy(entityIdFromSession())
                                    .output(d -> d.t1()) :
                                onEvent(shape.validResponseAndRejected()).to(Failed).masked(shape::maskedResponse)
                                    .assembleInput()
                                    .output(d -> d)
                        )
                        .otherwise(
                            shape.onValidResponseUnknown() != null ?
                                onEvent(shape.validResponseAndUnknown()).to(Unknown).masked(shape::maskedResponse)
                                    .assembleInput()
                                    .trigger(shape.onValidResponseUnknown().eventType())
                                    .with(shape.onValidResponseUnknown().dataAdapter())
                                    .on(shape.processModel())
                                    .identifiedBy(entityIdFromSession())
                                    .output(d -> d.t1()) :
                                onEvent(shape.validResponseAndUnknown()).to(Unknown).masked(shape::maskedResponse)
                                    .assembleInput()
                                    .output(d -> d)
                        ),
                    d -> HttpResponse.create(
                        d.t1().statusCode(),
                        d.t1().reasonPhrase(),
                        d.t1().headers(),
                        d.t2().validValue()
                    )
                ).otherwise(
                    onEvent(shape.invalidResponse()).to(Intermediate)
                        .assembleInput()
                        .when(shape.isRejectedByInvalidResponse())
                        .then(
                            shape.onInvalidResponseRejection() != null ?
                                onEvent(shape.invalidResponseAndRejected()).to(Failed).masked(shape.masking()::mask)
                                    .assembleInput()
                                    .trigger(shape.onInvalidResponseRejection().eventType())
                                    .with(shape.onInvalidResponseRejection().dataAdapter())
                                    .on(shape.processModel())
                                    .identifiedBy(entityIdFromSession())
                                    .output(d -> d.t1().t1()) :
                                onEvent(shape.invalidResponseAndRejected()).to(Failed).masked(shape.masking()::mask)
                                    .assembleInput()
                                    .output(d -> d.t1())
                        )
                        .otherwise(
                            shape.onInvalidResponseUnknown() != null ?
                                onEvent(shape.invalidResponseAndUnknown()).to(Unknown).masked(shape.masking()::mask)
                                    .assembleInput()
                                    .trigger(shape.onInvalidResponseUnknown().eventType())
                                    .with(shape.onInvalidResponseUnknown().dataAdapter())
                                    .on(shape.processModel())
                                    .identifiedBy(entityIdFromSession())
                                    .output(d -> d.t1().t1()) :
                                onEvent(shape.invalidResponseAndUnknown()).to(Unknown).masked(shape.masking()::mask)
                                    .assembleInput()
                                    .output(d -> d.t1())
                        ),
                    d -> tuple(d.t1(), d.t2().invalidReason())
                )
        ),
        Intermediate, List.of(),
        Failed, List.of(),
        Accepted, rollbackOnAcceptedModel != null ? List.of(
                onEvent(this.<RI>rollbackDispatched()).to(RolledBack)
                    .assemble(c -> tuple(rollbackContext(c), tuple(c.input(), shape.dispatched(c.log()).t1())))
                    .trigger(rollbackRequestDispatched.apply(rollbackOnAcceptedModel))
                    .withReactive(d -> rollbackMessageCreator.apply(d.t1(), d.t2()))
                    .on(rollbackOnAcceptedModel)
                    .identifiedBy(newEntityId())
                    .output()
            ) : List.of(),
        Unknown, rollbackOnUnknownModel != null ? List.of(
            onEvent(this.<RI>rollbackDispatched()).to(RolledBack)
                .assemble(c -> tuple(rollbackContext(c), tuple(c.input(), shape.dispatched(c.log()).t1())))
                .trigger(rollbackRequestDispatched.apply(rollbackOnUnknownModel))
                .withReactive(d -> rollbackMessageCreator.apply(d.t1(), d.t2()))
                .on(rollbackOnUnknownModel)
                .identifiedBy(newEntityId())
                .output()
        ) : List.of(),
        RolledBack, List.of()
    );
  }

  @Override
  public String name() {
    return name;
  }

  @Override
  public UUID id() {
    return id;
  }

  /// The context which the rollback request is dispatched in: the process which rolls this request back.
  private static DispatchContext rollbackContext(TransitionContext<?> c) {
    return new DispatchContext(c.triggerEvent().entityId(), c.correlationId(), c.timestamp());
  }

  /// The transitions of this outbox request. Unless it is the copy of [#withRollback(BiFunction)] or
  /// [#withBatchedRollback(BiFunction)], they have no rollback message creator, so a rollback of the request fails.
  @Override
  public Map<State, List<TransitionModel<?, ?>>> transitions() {
    var kept = transitions;
    // Asked for by a creator of a rollback model, before the rollback models are there to trigger on
    return kept != null ? kept.get() : createTransitions();
  }

  private Map<State, List<TransitionModel<?, ?>>> createTransitions() {
    return transitionsCreator.apply(this);
  }

  /// A copy of this outbox request, with the same name and id, whose transitions roll the request back with the
  /// rollback request message created by the given rollback message creator, from the data the rollback is dispatched
  /// with (of type `RI`) and the request being rolled back, and the context of the process rolling the request back.
  /// Requests are triggered on the copy instead of this, so it is the one the state machine runs. The copy is made once
  /// for each rollback message creator, so dispatchers with the same creator share it, while copies with different
  /// creators are different entity models with the same id, of which a state machine runs one.
  public <RI> AtMostOnce<RQ, RRQ> withRollback(
      BiFunction<DispatchContext, Tuple2<RI, HttpRequest<RQ>>, Mono<HttpRequest<RRQ>>> rollbackMessageCreator
  ) {
    return copy(false, rollbackMessageCreator, outbox -> outbox.transitions(outbox.shape, AtLeastOnce::requestDispatched, rollbackMessageCreator));
  }

  /// As [#withRollback(BiFunction)], with the rollback requests dispatched as part of a batch.
  public <RI> AtMostOnce<RQ, RRQ> withBatchedRollback(
      BiFunction<DispatchContext, Tuple2<RI, HttpRequest<RQ>>, Mono<BatchedRequest<RRQ>>> rollbackMessageCreator
  ) {
    return copy(true, rollbackMessageCreator, outbox -> outbox.transitions(outbox.shape, AtLeastOnce::batchedRequestDispatched, rollbackMessageCreator));
  }

  /// The copy with the rollback message creator, made the first time it is asked for
  private AtMostOnce<RQ, RRQ> copy(
      boolean batched,
      Object rollbackMessageCreator,
      Function<AtMostOnce<RQ, RRQ>, Map<State, List<TransitionModel<?, ?>>>> transitionsCreator
  ) {
    synchronized (copies) {
      return copies.computeIfAbsent(new CopyKey(batched, rollbackMessageCreator), _ -> new AtMostOnce<>(this, transitionsCreator));
    }
  }

  /// A rollback message creator, compared by identity, and whether the rollback requests are batched
  private record CopyKey(boolean batched, Object rollbackMessageCreator) {
    @Override public boolean equals(Object o) {
      return o instanceof CopyKey(boolean b, Object c) && b == batched && c == rollbackMessageCreator;
    }
    @Override public int hashCode() {
      return System.identityHashCode(rollbackMessageCreator) * 31 + Boolean.hashCode(batched);
    }
  }

  @Override
  public String toString() {
    return name;
  }

  enum States implements State {
    InFlight,
    Failed,
    Accepted,
    Unknown,
    RolledBack
  }

  private static List<TransitionModel<?, ?>> list(TransitionModel<?, ?>... transitions) {
    ArrayList<TransitionModel<?, ?>> list = new ArrayList<>(transitions.length);
    for (TransitionModel<?, ?> transition : transitions) {
      if (transition != null) list.add(transition);
    }
    return list;
  }

}
