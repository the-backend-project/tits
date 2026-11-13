package com.github.thxmasj.backend.http.outbox;

import static com.github.thxmasj.backend.core.EntitySelector.entityId;
import static com.github.thxmasj.backend.core.EntitySelector.newEntityId;
import static com.github.thxmasj.backend.core.TransitionModelBuilder.WithEvent.onEvent;
import static com.github.thxmasj.backend.core.Tuples.tuple;
import static com.github.thxmasj.backend.http.outbox.AtLeastOnce.States.Accepted;
import static com.github.thxmasj.backend.http.outbox.AtLeastOnce.States.Compensating;
import static com.github.thxmasj.backend.http.outbox.AtLeastOnce.States.Dead;
import static com.github.thxmasj.backend.http.outbox.AtLeastOnce.States.InFlight;
import static com.github.thxmasj.backend.http.outbox.AtLeastOnce.States.Intermediate;
import static com.github.thxmasj.backend.http.outbox.EventTypes.ConnectionDropped;
import static com.github.thxmasj.backend.http.outbox.EventTypes.ConnectionFailed;
import static com.github.thxmasj.backend.http.outbox.EventTypes.ResponseReceived;
import static com.github.thxmasj.backend.http.outbox.EventTypes.TimeoutExpired;
import static com.github.thxmasj.backend.http.outbox.HttpOutboxRequest.toHttpRequest;

import com.github.thxmasj.backend.core.BasicEventType;
import com.github.thxmasj.backend.core.DataType;
import com.github.thxmasj.backend.core.EventLog;
import com.github.thxmasj.backend.core.EventType;
import com.github.thxmasj.backend.core.State;
import com.github.thxmasj.backend.core.TransitionModelBuilder.TransitionContext;
import com.github.thxmasj.backend.core.TransitionModel;
import com.github.thxmasj.backend.core.Tuples.Tuple2;
import com.github.thxmasj.backend.core.Validated;
import com.github.thxmasj.backend.core.http.HttpClient;
import com.github.thxmasj.backend.core.http.HttpDataType;
import com.github.thxmasj.backend.http.outbox.HttpOutboxRequest.BatchedRequest;
import com.github.thxmasj.backend.core.message.http.HttpResponseMessage;
import com.github.thxmasj.backend.core.message.http.TypedHttpRequest;
import com.github.thxmasj.backend.core.message.http.TypedHttpResponse;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;

public final class AtLeastOnce<RQ> implements HttpOutboxRequest<RQ> {

  private final String name;
  private final UUID id;
  private final Shape<RQ, ?, ?> shape;

  private record Shape<RQ, RS, S>(
      DataType<RQ> requestPayloadType,
      DataType<RS> responsePayloadType,
      BiFunction<TransitionContext<Void>, TypedHttpRequest<RQ>, TypedHttpRequest<RQ>> repeatMessageCreator,
      ForwardAction forward,
      Duration inflightTimeout,
      com.github.thxmasj.backend.core.EntityModel processModel,
      Callback<TypedHttpResponse<RS>, S> onSuccess,
      Function<HttpResponseMessage, Validated<RS>> contentParser,
      BiFunction<TypedHttpRequest<RQ>, HttpResponseMessage, Validated<RS>> requestAwareContentParser,
      Predicate<TypedHttpResponse<RS>> isAccepted,
      Predicate<TypedHttpResponse<RS>> isFailureTransient,
      Predicate<Tuple2<HttpResponseMessage, String>> isRejectedByInvalidResponse,
      Predicate<Tuple2<HttpResponseMessage, String>> isFailureByInvalidResponseTransient,
      Predicate<RetryContext> isAttemptAvailable,
      Function<RetryContext, Duration> backoffAlgorithm
  ) {

    Function<TransitionContext<Void>, RetryContext> retry() {
      return c -> new RetryContext(
          c.log().created(),
          c.timestamp(),
          attempts(c.log())
      );
    }

    long attempts(EventLog log) {
      return log.count(attemptsAvailable()) + 1;
    }

    EventType<RetryContext, Void> attemptsAvailable() {
      return BasicEventType.of(
          "Attempts available",
          UUID.fromString("e6d6331f-5891-4d97-b699-ffe0dcf9d6cb"),
          DataType.json(RetryContext.class),
          DataType.none()
      );
    }

    EventType<Void, Void> attemptsExhausted() {
      return BasicEventType.of(
          "Attempts exhausted",
          UUID.fromString("19a29ca5-6021-41e7-b247-74fd3b8389dd")
      );
    }

    EventType<TypedHttpRequest<RQ>, Tuple2<TypedHttpRequest<RQ>, UUID>> requestDispatched() {
      return BasicEventType.of(
          "Request dispatched",
          UUID.fromString("736f1f23-dba8-467e-a503-a9e2c516197e"),
          HttpDataType.forRequest(requestPayloadType),
          DataType.tuple(HttpDataType.forRequest(requestPayloadType), DataType.uuid())
      );
    }

    EventType<BatchedRequest<RQ>, Tuple2<TypedHttpRequest<RQ>, UUID>> batchedRequestDispatched() {
      return BasicEventType.of(
          "Batched request dispatched",
          UUID.fromString("a0f1c0d9-3d55-4a0e-9a8f-5d3a6d9c2b71"),
          BatchedRequest.dataType(requestPayloadType),
          DataType.tuple(HttpDataType.forRequest(requestPayloadType), DataType.uuid())
      );
    }

    /// The request message and the process id of the dispatched request, whether batched or not.
    Tuple2<TypedHttpRequest<RQ>, UUID> dispatched(EventLog log) {
      return log.one(requestDispatched(), batchedRequestDispatched());
    }

    Optional<Tuple2<TypedHttpRequest<RQ>, UUID>> dispatchedIfExists(EventLog log) {
      return log.oneIfExists(requestDispatched()).or(() -> log.oneIfExists(batchedRequestDispatched()));
    }

    EventType<Void, TypedHttpRequest<RQ>> requestRedispatched() {
      return BasicEventType.of(
          "Request redispatched",
          UUID.fromString("a4be4a1a-2b51-42f5-851d-ea3d74504633"),
          DataType.none(),
          HttpDataType.forRequest(requestPayloadType)
      );
    }
    
    // Intermediate
    EventType<TypedHttpResponse<RS>, TypedHttpResponse<RS>> validResponse() {
      return BasicEventType.of(
          "[valid response]",
          UUID.fromString("c9fcf4d6-95f8-418f-aa6b-c3d987d3a3c3"),
          DataType.unknown(),
          DataType.unknown()
      );
    }

    EventType<TypedHttpResponse<RS>, TypedHttpResponse<RS>> requestReceivedAndRejected() {
      return BasicEventType.of(
          "[request rejected]",
          UUID.fromString("5362567f-792e-4f8a-81d6-3b201d44d3f0"),
          DataType.unknown(),
          HttpDataType.forResponse(responsePayloadType)
      );
    }

    EventType<Void, TypedHttpResponse<RS>> requestReceivedAndRejectedTransiently() {
      return BasicEventType.of(
          "[request rejected transiently]",
          UUID.fromString("bcdf93f4-657d-4b26-b165-74279a5ea477"),
          DataType.unknown(),
          HttpDataType.forResponse(responsePayloadType)
      );
    }

    EventType<TypedHttpResponse<RS>, TypedHttpResponse<RS>> requestReceivedAndRejectedPermanently() {
      return BasicEventType.of(
          "[request rejected permanently]",
          UUID.fromString("94b1169f-8e09-45d5-bdff-cae9b146db30"),
          DataType.unknown(),
          HttpDataType.forResponse(responsePayloadType)
      );
    }

    EventType<Tuple2<HttpResponseMessage, String>, HttpResponseMessage> invalidResponseAndRejectedPermanently() {
      return BasicEventType.of(
          "[invalid response, request rejected permanently]",
          UUID.fromString("94eca92d-a229-4aa9-bc02-48260fa1bbf0"),
          DataType.unknown(),
          HttpDataType.forResponse()
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
  }

  @Override
  public EventType<TypedHttpRequest<RQ>, ?> requestDispatched() {
    return shape.requestDispatched();
  }

  @Override
  public EventType<BatchedRequest<RQ>, ?> batchedRequestDispatched() {
    return shape.batchedRequestDispatched();
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
    return request(false, shape, log);
  }

  public boolean scheduledRedispatch(EventLog log) {
    return log.exists(shape.attemptsAvailable());
  }

  public TypedHttpRequest<RQ> redispatchedRequest(EventLog log) {
    return log.oneIfExists(shape.requestRedispatched()).orElse(null);
  }

  public TypedHttpRequest<RQ> request(boolean optional, EventLog log) {
    return request(optional, shape, log);
  }

  private TypedHttpRequest<RQ> request(boolean optional, Shape<RQ, ?, ?> shape, EventLog log) {
    return optional ?
        log.oneIfExists(shape.requestRedispatched())
            .orElse(shape.dispatchedIfExists(log).map(d -> d.t1()).orElse(null)) :
        shape.dispatched(log).t1();
  }

  @Override
  public TypedHttpResponse<?> response(EventLog log) {
    return response(shape, log);
  }

  private <RS> TypedHttpResponse<RS> response(Shape<RQ, RS, ?> shape, EventLog log) {
    return log.oneIfExists(shape.requestAccepted())
        .orElse(log.oneIfExists(shape.invalidResponseAndRejectedPermanently())
            .map(d -> new TypedHttpResponse<>(d.statusCode(), d.reasonPhrase(), d.headers(), (RS) null))
            .orElse(log.oneIfExists(shape.requestReceivedAndRejectedPermanently())
                .orElse(null)));
  }

  public record RetryContext(
      ZonedDateTime enqueueTime,
      ZonedDateTime now,
      long attemptNumber
  ) {}

  public <S, RS> AtLeastOnce(
      String name,
      UUID id,
      DataType<RQ> requestPayloadType,
      DataType<RS> responsePayloadType,
      BiFunction<TransitionContext<Void>, TypedHttpRequest<RQ>, TypedHttpRequest<RQ>> repeatMessageCreator,
      HttpClient forwarder,
      Duration inflightTimeout,
      com.github.thxmasj.backend.core.EntityModel processModel,
      Callback<TypedHttpResponse<RS>, S> onSuccess,
      Function<HttpResponseMessage, Validated<RS>> contentParser,
      BiFunction<TypedHttpRequest<RQ>, HttpResponseMessage, Validated<RS>> requestAwareContentParser,
      Predicate<TypedHttpResponse<RS>> isAccepted,
      Predicate<TypedHttpResponse<RS>> isFailureTransient,
      Predicate<Tuple2<HttpResponseMessage, String>> isRejectedByInvalidResponse,
      Predicate<Tuple2<HttpResponseMessage, String>> isFailureByInvalidResponseTransient,
      Predicate<RetryContext> isAttemptAvailable,
      Function<RetryContext, Duration> backoffAlgorithm
  ) {
    this.name = name;
    this.id = id;
    this.shape = new Shape<>(
        requestPayloadType,
        responsePayloadType,
        repeatMessageCreator,
        new ForwardAction(forwarder),
        inflightTimeout,
        processModel,
        onSuccess,
        contentParser,
        requestAwareContentParser,
        isAccepted,
        isFailureTransient,
        isRejectedByInvalidResponse,
        isFailureByInvalidResponseTransient,
        isAttemptAvailable,
        backoffAlgorithm
    );
  }

  public Map<State, List<TransitionModel<?, ?>>> inflightTransitions() {
    return inflightTransitions(shape);
  }

  private <RS, S> Map<State, List<TransitionModel<?, ?>>> inflightTransitions(
      Shape<RQ, RS, S> shape
  ) {
    // Intermediate
    EventType<Tuple2<HttpResponseMessage, String>, Tuple2<HttpResponseMessage, String>> invalidResponse = BasicEventType.of(
        "[invalid response]",
        UUID.fromString("3bd020c3-caf8-4a9b-a10c-d818f98a6de7"),
        DataType.unknown()
    );
    // Intermediate
    EventType<Tuple2<HttpResponseMessage, String>, HttpResponseMessage> invalidResponseAndRejected = BasicEventType.of(
        "[invalid response, request rejected]",
        UUID.fromString("402f9bf4-855c-4383-ac54-3375f9d156d1"),
        DataType.unknown(),
        HttpDataType.forResponse()
    );
    EventType<Void, HttpResponseMessage> invalidResponseAndRejectedTransiently = BasicEventType.of(
        "[invalid response, request rejected transiently]",
        UUID.fromString("9f121370-4b8d-4a08-a7ef-30555c1e1f48"),
        DataType.unknown(),
        HttpDataType.forResponse()
    );
    // Leaf
    EventType<Void, HttpResponseMessage> invalidResponseAndUnknown = BasicEventType.of(
        "[invalid response, unknown status]",
        UUID.fromString("ecfb2c9d-178b-4c3e-b09e-c580e09b01b4"),
        DataType.unknown(),
        HttpDataType.forResponse()
    );
    return Map.of(
        InFlight, List.of(
            onEvent(ConnectionFailed).to(Intermediate)
                .assemble(shape.retry())
                .when(shape.isAttemptAvailable)
                .then(
                    onEvent(shape.attemptsAvailable()).to(Compensating)
                        .assembleInput()
                        .trigger(shape.requestRedispatched()).after(shape.backoffAlgorithm)
                        .output(),
                    d -> d
                )
                .otherwise(onEvent(shape.attemptsExhausted()).to(Dead).output(), _ -> null),
            onEvent(ConnectionDropped).to(Intermediate)
                .assemble(shape.retry())
                .when(shape.isAttemptAvailable)
                .then(
                    onEvent(shape.attemptsAvailable()).to(Compensating)
                        .assembleInput()
                        .trigger(shape.requestRedispatched()).after(shape.backoffAlgorithm)
                        .output(),
                    d -> d
                )
                .otherwise(onEvent(shape.attemptsExhausted()).to(Dead).output(), _ -> null),
            onEvent(TimeoutExpired).to(Intermediate)
                .assemble(shape.retry())
                .when(shape.isAttemptAvailable())
                .then(
                    onEvent(shape.attemptsAvailable()).to(Compensating)
                        .assembleInput()
                        .trigger(shape.requestRedispatched()).after(shape.backoffAlgorithm)
                        .output(),
                    d -> d
                )
                .otherwise(onEvent(shape.attemptsExhausted()).to(Dead).output(), _ -> null),
            onEvent(ResponseReceived).to(Intermediate)
                .assemble(c -> tuple(
                    c.input(),
                    shape.requestAwareContentParser != null ?
                        shape.requestAwareContentParser.apply(
                            shape.dispatched(c.log()).t1(),
                            c.input()
                        ) :
                        shape.contentParser.apply(c.input())
                ))
                .when(d -> d.t2().isValid())
                .then(
                    validResponseTransition(shape),
                    d -> TypedHttpResponse.create(
                        d.t1().statusCode(),
                        d.t1().reasonPhrase(),
                        d.t1().headers(),
                        d.t2().validValue()
                    )
                ).otherwise(
                    onEvent(invalidResponse).to(Intermediate)
                        .assembleInput()
                        .when(shape.isRejectedByInvalidResponse)
                        .then(
                            onEvent(invalidResponseAndRejected).to(Dead)
                                .assembleInput()
                                .when(shape.isFailureByInvalidResponseTransient)
                                .then(
                                    onEvent(invalidResponseAndRejectedTransiently).to(Intermediate)
                                        .assemble(shape.retry())
                                        .when(shape.isAttemptAvailable())
                                        .then(
                                            onEvent(shape.attemptsAvailable()).to(Compensating)
                                                .assembleInput()
                                                .trigger(shape.requestRedispatched()).after(shape.backoffAlgorithm)
                                                .output(),
                                            d -> d
                                        )
                                        .otherwise(onEvent(shape.attemptsExhausted()).to(Dead).output(), _ -> null),
                                    _ -> null
                                )
                                .otherwise(
                                    onEvent(shape.invalidResponseAndRejectedPermanently()).to(Dead)
                                        .assembleInput()
                                        .output(d -> d.t1())
                                )
                        )
                        .otherwise(
                            onEvent(invalidResponseAndUnknown).to(Intermediate)
                                .assemble(shape.retry())
                                .when(shape.isAttemptAvailable())
                                .then(
                                    onEvent(shape.attemptsAvailable()).to(Compensating)
                                        .assembleInput()
                                        .trigger(shape.requestRedispatched()).after(shape.backoffAlgorithm())
                                        .output(),
                                    d -> d
                                )
                                .otherwise(onEvent(shape.attemptsExhausted()).to(Dead).output(), _ -> null),
                            _ -> null
                        ),
                    d -> tuple(d.t1(), d.t2().invalidReason())
                )
        ),
        Compensating, List.of(
            onEvent(shape.requestRedispatched()).to(InFlight)
                .assemble(c -> shape.repeatMessageCreator().apply(c, shape.dispatched(c.log()).t1()))
                .trigger(shape.forward()).with(d -> toHttpRequest(d, shape.requestPayloadType()))
                .trigger(TimeoutExpired).after(_ -> shape.inflightTimeout())
                .output(d -> d)
        ),
        Intermediate, List.of(),
        Dead, List.of(),
        Accepted, List.of()
    );
  }

  private <RS, S> TransitionModel<TypedHttpResponse<RS>, TypedHttpResponse<RS>> validResponseTransition(
      Shape<RQ, RS, S> shape
  ) {
    return onEvent(shape.validResponse()).to(Intermediate)
            .assemble(c -> c.input())
            .when(shape.isAccepted)
            .then(requestAcceptedTransition(shape))
            .otherwise(requestReceivedAndRejectedTransition(shape));
  }

  private <RS, S> TransitionModel<TypedHttpResponse<RS>, TypedHttpResponse<RS>> requestAcceptedTransition(
      Shape<RQ, RS, S> shape
  ) {
    return shape.onSuccess() != null ?
        onEvent(shape.requestAccepted()).to(Accepted)
            .assemble(c -> tuple(c.input(), shape.dispatched(c.log()).t2()))
            .trigger(shape.onSuccess().eventType())
            .with(d -> shape.onSuccess().dataAdapter().apply(d.t1()))
            .on(shape.processModel())
            .identifiedBy(d -> entityId(d.t2()))
            .output(d -> d.t1().t1()) :
        onEvent(shape.requestAccepted()).to(Accepted)
            .assembleInput()
            .output(d -> d);
  }

  private <RS, S> TransitionModel<TypedHttpResponse<RS>, TypedHttpResponse<RS>> requestReceivedAndRejectedTransition(
      Shape<RQ, RS, S> shape
  ) {
    return onEvent(shape.requestReceivedAndRejected()).to(Intermediate)
        .assembleInput()
        .when(shape.isFailureTransient())
        .then(
            onEvent(shape.requestReceivedAndRejectedTransiently()).to(Intermediate)
                .assemble(shape.retry())
                .when(shape.isAttemptAvailable())
                .then(
                    onEvent(shape.attemptsAvailable()).to(Compensating)
                        .assembleInput()
                        .trigger(shape.requestRedispatched()).after(shape.backoffAlgorithm())
                        .output(),
                    d -> d
                )
                .otherwise(onEvent(shape.attemptsExhausted()).to(Dead).output(), _ -> null),
            _ -> null
        )
        .otherwise(
            onEvent(shape.requestReceivedAndRejectedPermanently()).to(Dead)
                .assembleInput()
                .output(d -> d)
        );
  }

  public long attempts(EventLog log) {
    return shape.attempts(log);
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

  private <RS, S> Map<State, List<TransitionModel<?, ?>>> transitions(Shape<RQ, RS, S> shape) {
    return HttpOutboxRequest.combine(
        Map.of(
            Begin, List.of(
                onEvent(shape.requestDispatched()).to(InFlight)
                    .assemble(c -> tuple(
                        c.input(),
                        c.triggerEvent(), // process id
                        c.log().entityId() // my id
                    ))

                    .trigger(ProcessReferenced)
                    .with(d -> d.t2().entityId())
                    .on(Index)
                    .identifiedBy(d -> newEntityId(d.t3()))

                    .trigger(shape.forward)
                    .with(d -> toHttpRequest(d.t1().t1(), shape.requestPayloadType))

                    .trigger(TimeoutExpired)
                    .after(_ -> shape.inflightTimeout)

                    .output(d -> tuple(d.t1().t1(), d.t1().t2().entityId())),
                onEvent(shape.batchedRequestDispatched()).to(InFlight)
                    .assemble(c -> tuple(
                        c.input().request(),
                        c.triggerEvent(), // process id
                        c.log().entityId(), // my id
                        c.input().batchId()
                    ))

                    .trigger(ProcessReferenced)
                    .with(d -> d.t2().entityId())
                    .on(Index)
                    .identifiedBy(d -> newEntityId(d.t3()))

                    .trigger(BatchReferenced)
                    .with(d -> d.t1().t4())
                    .on(Index)
                    .identifiedBy(d -> entityId(d.t1().t3()))

                    .trigger(shape.forward)
                    .with(d -> toHttpRequest(d.t1().t1().t1(), shape.requestPayloadType))

                    .trigger(TimeoutExpired)
                    .after(_ -> shape.inflightTimeout)

                    .output(d -> tuple(d.t1().t1().t1(), d.t1().t1().t2().entityId()))
            )
        ), inflightTransitions(shape)
    );
  }

  @Override
  public String toString() {
    return name;
  }

  public enum States implements State {
    InFlight,
    Accepted,
    Compensating,
    Dead
  }

}
