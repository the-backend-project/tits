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
import static com.github.thxmasj.statemachine.http.outbox.AtMostOnce.States.Unknown;
import static com.github.thxmasj.statemachine.http.outbox.EventTypes.ConnectionDropped;
import static com.github.thxmasj.statemachine.http.outbox.EventTypes.ConnectionFailed;
import static com.github.thxmasj.statemachine.http.outbox.EventTypes.ResponseReceived;
import static com.github.thxmasj.statemachine.http.outbox.EventTypes.TimeoutExpired;
import static com.github.thxmasj.statemachine.http.outbox.HttpOutboxRequest.toHttpRequest;

import com.github.thxmasj.statemachine.BasicEventType;
import com.github.thxmasj.statemachine.BasicEventType.ReadOnly;
import com.github.thxmasj.statemachine.DataType;
import com.github.thxmasj.statemachine.EntityModel;
import com.github.thxmasj.statemachine.EventLog;
import com.github.thxmasj.statemachine.EventType;
import com.github.thxmasj.statemachine.State;
import com.github.thxmasj.statemachine.TransitionModelBuilder.TransitionContext;
import com.github.thxmasj.statemachine.TransitionModelBuilder.TransitionModel;
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

public final class AtMostOnce<I, RI> implements HttpOutboxRequest<I> {

  private final String name;
  private final UUID id;
  private final AtLeastOnce<RI> rollbackModel;
  private final Shape<I, ?, ?, ?, ?, ?, ?, ?, ?, ?> shape;

  public record Projection<RQ, RS>(
      TypedHttpRequest<RQ> request,
      TypedHttpResponse<RS> response,
      AtLeastOnce.Projection<?, ?> rollbackProjection
  ) {}

  private record Shape<I, T1, T2, T3, T4, T5, T6, T7, RQ, RS>(
      DataType<RQ> requestPayloadType,
      DataType<RS> responsePayloadType,
      Function<TransitionContext<I>, Mono<TypedHttpRequest<RQ>>> messageCreator,
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
    EventType<I, Tuple2<TypedHttpRequest<RQ>, UUID>> requestDispatched() {
      return BasicEventType.of(
          "Request dispatched (AMO)",
          UUID.fromString("3f22bfef-dcb4-4560-8b88-6b5040433319"),
          DataType.unknown(),
          DataType.tuple(HttpDataType.forRequest(requestPayloadType), DataType.uuid())
      );
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
          UUID.fromString("0f8fe1c2-2d29-406c-87b5-f9f43a03a54f"),
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
  public EventType<I, ?> requestDispatched() {
    return shape.requestDispatched();
  }

  public EventType<RI, ?> rollbackDispatched() {
    return rollbackModel != null ? rollbackModel.requestDispatched() : null;
  }

  @Override
  public DataType<?> requestPayloadType() {
    return shape.requestPayloadType();
  }

  @Override
  public DataType<?> responsePayloadType() {
    return shape.responsePayloadType();
  }

  @Override
  public TypedHttpRequest<?> request(EventLog log) {
    return log.one(shape.requestDispatched()).t1();
  }

  @Override
  public TypedHttpResponse<?> response(EventLog log) {
    return response(shape, log);
  }

  private <RS> TypedHttpResponse<RS> response(Shape<I, ?, ?, ?, ?, ?, ?, ?, ?, RS> shape, EventLog log) {
    return log.oneIfExists(shape.requestAccepted())
        .orElse(log.oneIfExists(shape.validResponseAndRejected())
            .orElse(log.oneIfExists(shape.validResponseAndUnknown()).orElse(null)));
  }

  public AtLeastOnce<RI> rollbackModel() {
    return rollbackModel;
  }

  public <T1, T2, T3, T4, T5, T6, T7, RQ, RS> AtMostOnce(
      String name,
      UUID id,
      DataType<RQ> requestPayloadType,
      DataType<RS> responsePayloadType,
      Function<TransitionContext<I>, Mono<TypedHttpRequest<RQ>>> messageCreator,
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
      AtLeastOnce<RI> rollbackModel
  ) {
    this(
        name,
        id,
        requestPayloadType,
        responsePayloadType,
        messageCreator,
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
        _ -> rollbackModel
    );
  }

  public <T1, T2, T3, T4, T5, T6, T7, RQ, RS> AtMostOnce(
      String name,
      UUID id,
      DataType<RQ> requestPayloadType,
      DataType<RS> responsePayloadType,
      Function<TransitionContext<I>, Mono<TypedHttpRequest<RQ>>> messageCreator,
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
      Function<AtMostOnce<I, RI>, AtLeastOnce<RI>> rollbackModelSupplier
  ) {
    this.name = name;
    this.id = id;
    this.shape = new Shape<>(
        requestPayloadType,
        responsePayloadType,
        messageCreator,
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
    this.rollbackModel = rollbackModelSupplier != null ? rollbackModelSupplier.apply(this) : null;
  }

  public EventType<Void, Projection<?, ?>> Get = new ReadOnly<>(
      "Get",
      UUID.fromString("401ca27d-8f51-495c-88aa-2a0d1248aad6"),
      DataType.none(),
      DataType.unknown() // Never marshaling, so...
  );

  private <T1, T2, T3, T4, T5, T6, T7, RQ, RS> Map<State, List<TransitionModel<?, ?>>> transitions(Shape<I, T1, T2, T3, T4, T5, T6, T7, RQ, RS> shape) {
    return HttpOutboxRequest.combine(rollbackModel != null ? rollbackModel.inflightTransitions() : Map.of(), Map.of(
        Begin, List.of(
            onEvent(shape.requestDispatched()).to(InFlight)
                .assembleReactive(c -> shape.messageCreator().apply(c).zipWith(Mono.just(tuple(c.triggerEvent(), c.log().entityId()))))
                .trigger(Indexed).with(d -> d.getT2().t1().entityId()).on(ProcessReference).identifiedBy(d -> newEntityId(d.getT2().t2().value()))
                .trigger(shape.forward()).with(d -> toHttpRequest(d.t1().getT1(), shape.requestPayloadType()))
                .trigger(TimeoutExpired).after(_ -> shape.inflightTimeout())
                .output(d -> tuple(d.t1().getT1(), d.t1().getT2().t1().entityId()))
        ),
        InFlight, list(
            rollbackModel != null ? rollbackModel.requestDispatchedTransition() : null,
            onEvent(Get).toSelf()
                .assemble((_, log) -> new Projection<>(log.one(shape.requestDispatched()).t1(), null, null))
                .output(d -> d),
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
                    .assemble(c -> tuple(c.log().entityModel(), c.log().one(shape.requestDispatched()).t2()))
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
                            c.log().one(shape.requestDispatched()).t1(),
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
        Failed, List.of(
            onEvent(Get).toSelf()
                .assemble((_, log) -> new Projection<>(
                    log.one(shape.requestDispatched()).t1(),
                    log.oneIfExists(shape.invalidResponseAndRejected())
                        .map(m -> new TypedHttpResponse<>(m.statusCode(), m.reasonPhrase(), m.headers(), (RS) null))
                        .orElse(log.oneIfExists(shape.validResponseAndRejected()).orElse(null)),
                    null
                ))
                .output(d -> d)
        ),
        Accepted, rollbackModel != null ? List.of(rollbackModel.requestDispatchedTransition()) : List.of(),
        Unknown, rollbackModel != null ? List.of(rollbackModel.requestDispatchedTransition()) : List.of()
    ));
  }

  @Override
  public String name() {
    return name;
  }

  @Override
  public UUID id() {
    return id;
  }

  @Override
  public Map<State, List<TransitionModel<?, ?>>> transitions() {
    return transitions(shape);
  }

  @Override
  public String toString() {
    return name;
  }

  enum States implements State {
    InFlight,
    Failed,
    Accepted,
    Unknown
  }

  private static List<TransitionModel<?, ?>> list(TransitionModel<?, ?>... transitions) {
    ArrayList<TransitionModel<?, ?>> list = new ArrayList<>(transitions.length);
    for (TransitionModel<?, ?> transition : transitions) {
      if (transition != null) list.add(transition);
    }
    return list;
  }

}
