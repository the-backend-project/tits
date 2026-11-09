package com.github.thxmasj.statemachine.http.outbox;

import static com.github.thxmasj.statemachine.EntitySelector.entityId;
import static com.github.thxmasj.statemachine.EntitySelector.entityIdFromSession;
import static com.github.thxmasj.statemachine.EntitySelector.newEntityId;
import static com.github.thxmasj.statemachine.TransitionModelBuilder.WithEvent.onEvent;
import static com.github.thxmasj.statemachine.Tuples.tuple;
import static com.github.thxmasj.statemachine.http.outbox.AtMostOnce.States.Accepted;
import static com.github.thxmasj.statemachine.http.outbox.AtMostOnce.States.Failed;
import static com.github.thxmasj.statemachine.http.outbox.AtMostOnce.States.InFlight;
import static com.github.thxmasj.statemachine.http.outbox.AtMostOnce.States.Intermediate;
import static com.github.thxmasj.statemachine.http.outbox.AtMostOnce.States.RolledBack;
import static com.github.thxmasj.statemachine.http.outbox.AtMostOnce.States.Unknown;
import static com.github.thxmasj.statemachine.http.outbox.EventTypes.ConnectionDropped;
import static com.github.thxmasj.statemachine.http.outbox.EventTypes.ConnectionFailed;
import static com.github.thxmasj.statemachine.http.outbox.EventTypes.ResponseReceived;
import static com.github.thxmasj.statemachine.http.outbox.EventTypes.TimeoutExpired;
import static com.github.thxmasj.statemachine.http.outbox.HttpOutboxRequest.toHttpRequest;

import com.github.thxmasj.statemachine.BasicEventType;
import com.github.thxmasj.statemachine.DataType;
import com.github.thxmasj.statemachine.EntityModel;
import com.github.thxmasj.statemachine.EventLog;
import com.github.thxmasj.statemachine.EventType;
import com.github.thxmasj.statemachine.State;
import com.github.thxmasj.statemachine.TransitionModel;
import com.github.thxmasj.statemachine.TransitionModelBuilder.TransitionContext;
import com.github.thxmasj.statemachine.Tuples.Tuple2;
import com.github.thxmasj.statemachine.Validated;
import com.github.thxmasj.statemachine.http.HttpClient;
import com.github.thxmasj.statemachine.http.HttpDataType;
import com.github.thxmasj.statemachine.message.http.HttpResponseMessage;
import com.github.thxmasj.statemachine.message.http.TypedHttpRequest;
import com.github.thxmasj.statemachine.message.http.TypedHttpResponse;
import java.time.Duration;
import java.util.ArrayList;
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
/// request message, it is kept outside the outbox request; it is given to [#transitions(BiFunction)] or
/// [#batchedRollbackTransitions(BiFunction)], e.g. by a [Dispatcher].
public final class AtMostOnce<RQ, RRQ> implements HttpOutboxRequest<RQ> {

  private final String name;
  private final UUID id;
  private final AtLeastOnce<RRQ> rollbackOnAcceptedModel;
  private final AtLeastOnce<RRQ> rollbackOnUnknownModel;
  private final Shape<?, ?, ?, ?, ?, ?, ?, RQ, ?> shape;

  private record Shape<T1, T2, T3, T4, T5, T6, T7, RQ, RS>(
      DataType<RQ> requestPayloadType,
      DataType<RS> responsePayloadType,
      ForwardAction forward,
      Duration inflightTimeout,
      com.github.thxmasj.statemachine.EntityModel processModel,
      Callback<EntityModel, T1> onPeerUnavailable,
      Callback<EntityModel, T2> onMissingResponse,
      Callback<TypedHttpResponse<RS>, T3> onSuccess,
      Callback<TypedHttpResponse<RS>, T4> onFailure,
      Callback<TypedHttpResponse<RS>, T7> onValidResponseUnknown, // Rollback
      Callback<Tuple2<HttpResponseMessage, String>, T5> onInvalidResponseRejection,
      Callback<Tuple2<HttpResponseMessage, String>, T6> onInvalidResponseUnknown, // Rollback
      Function<HttpResponseMessage, Validated<RS>> contentParser,
      BiFunction<TypedHttpRequest<RQ>, HttpResponseMessage, Validated<RS>> requestAwareContentParser,
      Predicate<TypedHttpResponse<RS>> isAccepted,
      Predicate<TypedHttpResponse<RS>> isRejected,
      Predicate<Tuple2<HttpResponseMessage, String>> isRejectedByInvalidResponse
  ) {
    EventType<TypedHttpRequest<RQ>, Tuple2<TypedHttpRequest<RQ>, UUID>> requestDispatched() {
      return BasicEventType.of(
          "Request dispatched",
          UUID.fromString("3f22bfef-dcb4-4560-8b88-6b5040433319"),
          HttpDataType.forRequest(requestPayloadType),
          DataType.tuple(HttpDataType.forRequest(requestPayloadType), DataType.uuid())
      );
    }

    EventType<BatchedRequest<RQ>, Tuple2<TypedHttpRequest<RQ>, UUID>> batchedRequestDispatched() {
      return BasicEventType.of(
          "Batched request dispatched",
          UUID.fromString("6c2e8f4b-1a7d-4f3e-b5c9-8e0d2a4f6b13"),
          BatchedRequest.dataType(requestPayloadType),
          DataType.tuple(HttpDataType.forRequest(requestPayloadType), DataType.uuid())
      );
    }

    /// The request message and the process id of the dispatched request, whether batched or not.
    Tuple2<TypedHttpRequest<RQ>, UUID> dispatched(EventLog log) {
      return log.one(requestDispatched(), batchedRequestDispatched());
    }
    // Intermediate
    EventType<TypedHttpResponse<RS>, TypedHttpResponse<RS>> validResponse() {
      return BasicEventType.of(
          "[valid response]",
          UUID.fromString("c9fcf4d6-95f8-418f-aa6b-c3d987d3a3c3"),
          HttpDataType.forResponse(responsePayloadType),
          HttpDataType.forResponse(responsePayloadType)
      );
    }
    // Intermediate
    EventType<Tuple2<HttpResponseMessage, String>, Tuple2<HttpResponseMessage, String>> invalidResponse() {
      return BasicEventType.of(
          "[invalid response]",
          UUID.fromString("3bd020c3-caf8-4a9b-a10c-d818f98a6de7"),
          DataType.tuple(HttpDataType.forResponse(), DataType.string()),
          DataType.tuple(HttpDataType.forResponse(), DataType.string())
      );
    }
    // Leaf
    EventType<TypedHttpResponse<RS>, TypedHttpResponse<RS>> requestAccepted() {
      return BasicEventType.of(
          "[request accepted]",
          RequestAcceptedId,
          HttpDataType.forResponse(responsePayloadType),
          HttpDataType.forResponse(responsePayloadType)
      );
    }
    // Leaf
    EventType<TypedHttpResponse<RS>, TypedHttpResponse<RS>> validResponseAndRejected() {
      return BasicEventType.of(
          "[request rejected]",
          UUID.fromString("5362567f-792e-4f8a-81d6-3b201d44d3f0"),
          HttpDataType.forResponse(responsePayloadType),
          HttpDataType.forResponse(responsePayloadType)
      );
    }
    // Leaf
    EventType<TypedHttpResponse<RS>, TypedHttpResponse<RS>> validResponseAndUnknown() {
      return BasicEventType.of(
          "[valid response, unknown status]",
          UUID.fromString("0059d0cc-e7ec-4f84-beb0-1870d00937d2"),
          HttpDataType.forResponse(responsePayloadType),
          HttpDataType.forResponse(responsePayloadType)
      );
    }
    // Leaf
    EventType<Tuple2<HttpResponseMessage, String>, HttpResponseMessage> invalidResponseAndRejected() {
      return BasicEventType.of(
          "[request rejected]",
          UUID.fromString("402f9bf4-855c-4383-ac54-3375f9d156d1"),
          DataType.tuple(HttpDataType.forResponse(), DataType.string()),
          HttpDataType.forResponse()
      );
    }
    // Leaf
    EventType<Tuple2<HttpResponseMessage, String>, HttpResponseMessage> invalidResponseAndUnknown() {
      return BasicEventType.of(
          "[invalid response, unknown status]",
          UUID.fromString("ecfb2c9d-178b-4c3e-b09e-c580e09b01b4"),
          DataType.tuple(HttpDataType.forResponse(), DataType.string()),
          HttpDataType.forResponse()
      );
    }

  }

  @Override
  public EventType<TypedHttpRequest<RQ>, ?> requestDispatched() {
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
  public TypedHttpRequest<RQ> request(EventLog log) {
    return shape.dispatched(log).t1();
  }

  @Override
  public TypedHttpResponse<?> response(EventLog log) {
    return response(shape, log);
  }

  private <RS> TypedHttpResponse<RS> response(Shape<?, ?, ?, ?, ?, ?, ?, ?, RS> shape, EventLog log) {
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
      com.github.thxmasj.statemachine.EntityModel processModel,
      Callback<EntityModel, T1> onPeerUnavailable,
      Callback<EntityModel, T2> onMissingResponse,
      Callback<TypedHttpResponse<RS>, T3> onSuccess,
      Callback<TypedHttpResponse<RS>, T4> onFailure,
      Callback<TypedHttpResponse<RS>, T7> onValidResponseUnknown, // Rollback
      Callback<Tuple2<HttpResponseMessage, String>, T5> onInvalidResponseRejection,
      Callback<Tuple2<HttpResponseMessage, String>, T6> onInvalidResponseUnknown, // Rollback
      Function<HttpResponseMessage, Validated<RS>> contentParser,
      BiFunction<TypedHttpRequest<RQ>, HttpResponseMessage, Validated<RS>> requestAwareContentParser,
      Predicate<TypedHttpResponse<RS>> isAccepted,
      Predicate<TypedHttpResponse<RS>> isRejected,
      Predicate<Tuple2<HttpResponseMessage, String>> isRejectedByInvalidResponse,
      AtLeastOnce<RRQ> rollbackOnAcceptedModel,
      AtLeastOnce<RRQ> rollbackOnUnknownModel
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
        _ -> rollbackOnUnknownModel
    );
  }

  public <T1, T2, T3, T4, T5, T6, T7, RS> AtMostOnce(
      String name,
      UUID id,
      DataType<RQ> requestPayloadType,
      DataType<RS> responsePayloadType,
      HttpClient forwarder,
      Duration inflightTimeout,
      com.github.thxmasj.statemachine.EntityModel processModel,
      Callback<EntityModel, T1> onPeerUnavailable,
      Callback<EntityModel, T2> onMissingResponse,
      Callback<TypedHttpResponse<RS>, T3> onSuccess,
      Callback<TypedHttpResponse<RS>, T4> onFailure,
      Callback<TypedHttpResponse<RS>, T7> onValidResponseUnknown, // Rollback
      Callback<Tuple2<HttpResponseMessage, String>, T5> onInvalidResponseRejection,
      Callback<Tuple2<HttpResponseMessage, String>, T6> onInvalidResponseUnknown, // Rollback
      Function<HttpResponseMessage, Validated<RS>> contentParser,
      BiFunction<TypedHttpRequest<RQ>, HttpResponseMessage, Validated<RS>> requestAwareContentParser,
      Predicate<TypedHttpResponse<RS>> isAccepted,
      Predicate<TypedHttpResponse<RS>> isRejected,
      Predicate<Tuple2<HttpResponseMessage, String>> isRejectedByInvalidResponse,
      Function<AtMostOnce<RQ, RRQ>, AtLeastOnce<RRQ>> rollbackOnAcceptedModelSupplier,
      Function<AtMostOnce<RQ, RRQ>, AtLeastOnce<RRQ>> rollbackOnUnknownModelSupplier
  ) {
    this.name = name;
    this.id = id;
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
        isRejectedByInvalidResponse
    );
    this.rollbackOnAcceptedModel = rollbackOnAcceptedModelSupplier != null ? rollbackOnAcceptedModelSupplier.apply(this) : null;
    this.rollbackOnUnknownModel = rollbackOnUnknownModelSupplier != null ? rollbackOnUnknownModelSupplier.apply(this) : null;
  }

  private <RI, RRI, T1, T2, T3, T4, T5, T6, T7, RS> Map<State, List<TransitionModel<?, ?>>> transitions(
      Shape<T1, T2, T3, T4, T5, T6, T7, RQ, RS> shape,
      Function<AtLeastOnce<RRQ>, EventType<RRI, ?>> rollbackRequestDispatched,
      BiFunction<DispatchContext, Tuple2<RI, TypedHttpRequest<RQ>>, Mono<RRI>> rollbackMessageCreator
  ) {
    return Map.of(
        Begin, List.of(
            onEvent(shape.requestDispatched()).to(InFlight)
                .assemble(c -> tuple(
                    c.input(),
                    c.triggerEvent(), // process id
                    c.log().entityId() // my id
                ))

                .trigger(ProcessReferenced)
                .with(d -> d.t2().entityId()) // process id
                .on(Index)
                .identifiedBy(d -> newEntityId(d.t3().value()))

                .trigger(shape.forward()).with(d -> toHttpRequest(d.t1().t1(), shape.requestPayloadType()))
                .trigger(TimeoutExpired).after(_ -> shape.inflightTimeout())
                .output(d -> tuple(d.t1().t1(), d.t1().t2().entityId())),
            onEvent(shape.batchedRequestDispatched()).to(InFlight)
                .assemble(c -> tuple(
                    c.input().request(),
                    c.triggerEvent(), // process id
                    c.log().entityId(), // my id
                    c.input().batchId()
                ))

                .trigger(ProcessReferenced)
                .with(d -> d.t2().entityId()) // process id
                .on(Index)
                .identifiedBy(d -> newEntityId(d.t3().value()))

                .trigger(BatchReferenced)
                .with(d -> d.t1().t4()) // batch id
                .on(Index)
                .identifiedBy(d -> entityId(d.t1().t3().value())) // NB: EntityId created above

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
                                onEvent(shape.requestAccepted()).to(Accepted)
                                    .assembleInput()
                                    .trigger(shape.onSuccess().eventType())
                                    .with(shape.onSuccess().dataAdapter())
                                    .on(shape.processModel())
                                    .identifiedBy(entityIdFromSession())
                                    .output(d -> d.t1()) :
                                onEvent(shape.requestAccepted()).to(Accepted)
                                    .assembleInput()
                                    .output(d -> d)
                        )
                        .when(shape.isRejected())
                        .then(
                            shape.onFailure() != null ?
                                onEvent(shape.validResponseAndRejected()).to(Failed)
                                    .assembleInput()
                                    .trigger(shape.onFailure().eventType())
                                    .with(shape.onFailure().dataAdapter())
                                    .on(shape.processModel())
                                    .identifiedBy(entityIdFromSession())
                                    .output(d -> d.t1()) :
                                onEvent(shape.validResponseAndRejected()).to(Failed)
                                    .assembleInput()
                                    .output(d -> d)
                        )
                        .otherwise(
                            shape.onValidResponseUnknown() != null ?
                                onEvent(shape.validResponseAndUnknown()).to(Unknown)
                                    .assembleInput()
                                    .trigger(shape.onValidResponseUnknown().eventType())
                                    .with(shape.onValidResponseUnknown().dataAdapter())
                                    .on(shape.processModel())
                                    .identifiedBy(entityIdFromSession())
                                    .output(d -> d.t1()) :
                                onEvent(shape.validResponseAndUnknown()).to(Unknown)
                                    .assembleInput()
                                    .output(d -> d)
                        ),
                    d -> TypedHttpResponse.create(
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
                                onEvent(shape.invalidResponseAndRejected()).to(Failed)
                                    .assembleInput()
                                    .trigger(shape.onInvalidResponseRejection().eventType())
                                    .with(shape.onInvalidResponseRejection().dataAdapter())
                                    .on(shape.processModel())
                                    .identifiedBy(entityIdFromSession())
                                    .output(d -> d.t1().t1()) :
                                onEvent(shape.invalidResponseAndRejected()).to(Failed)
                                    .assembleInput()
                                    .output(d -> d.t1())
                        )
                        .otherwise(
                            shape.onInvalidResponseUnknown() != null ?
                                onEvent(shape.invalidResponseAndUnknown()).to(Unknown)
                                    .assembleInput()
                                    .trigger(shape.onInvalidResponseUnknown().eventType())
                                    .with(shape.onInvalidResponseUnknown().dataAdapter())
                                    .on(shape.processModel())
                                    .identifiedBy(entityIdFromSession())
                                    .output(d -> d.t1().t1()) :
                                onEvent(shape.invalidResponseAndUnknown()).to(Unknown)
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

  /// The transitions of this outbox request, without a rollback message creator. Use [#transitions(BiFunction)] or
  /// [#batchedRollbackTransitions(BiFunction)] for an outbox request which can be rolled back, as its rollback fails
  /// otherwise.
  @Override
  public Map<State, List<TransitionModel<?, ?>>> transitions() {
    return transitions((_, _) -> Mono.error(new IllegalStateException(
        name + " has no rollback message creator. Use the transitions of its Dispatcher."
    )));
  }

  /// The transitions of this outbox request, with the rollback request message created by the given rollback message
  /// creator from the data the rollback is dispatched with (of type `RI`) and the request being rolled back, and the
  /// context of the process rolling the request back.
  public <RI> Map<State, List<TransitionModel<?, ?>>> transitions(
      BiFunction<DispatchContext, Tuple2<RI, TypedHttpRequest<RQ>>, Mono<TypedHttpRequest<RRQ>>> rollbackMessageCreator
  ) {
    return transitions(shape, AtLeastOnce::requestDispatched, rollbackMessageCreator);
  }

  /// As [#transitions(BiFunction)], with the rollback requests dispatched as part of a batch.
  public <RI> Map<State, List<TransitionModel<?, ?>>> batchedRollbackTransitions(
      BiFunction<DispatchContext, Tuple2<RI, TypedHttpRequest<RQ>>, Mono<BatchedRequest<RRQ>>> rollbackMessageCreator
  ) {
    return transitions(shape, AtLeastOnce::batchedRequestDispatched, rollbackMessageCreator);
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
