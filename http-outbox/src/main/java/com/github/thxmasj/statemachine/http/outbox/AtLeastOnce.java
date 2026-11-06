package com.github.thxmasj.statemachine.http.outbox;

import static com.github.thxmasj.statemachine.EntitySelector.entityId;
import static com.github.thxmasj.statemachine.EntitySelector.newEntityId;
import static com.github.thxmasj.statemachine.TransitionModelBuilder.WithEvent.onEvent;
import static com.github.thxmasj.statemachine.Tuples.tuple;
import static com.github.thxmasj.statemachine.http.outbox.AtLeastOnce.States.Accepted;
import static com.github.thxmasj.statemachine.http.outbox.AtLeastOnce.States.Compensating;
import static com.github.thxmasj.statemachine.http.outbox.AtLeastOnce.States.Dead;
import static com.github.thxmasj.statemachine.http.outbox.AtLeastOnce.States.InFlight;
import static com.github.thxmasj.statemachine.http.outbox.AtLeastOnce.States.Intermediate;
import static com.github.thxmasj.statemachine.http.outbox.EventTypes.ConnectionDropped;
import static com.github.thxmasj.statemachine.http.outbox.EventTypes.ConnectionFailed;
import static com.github.thxmasj.statemachine.http.outbox.EventTypes.ResponseReceived;
import static com.github.thxmasj.statemachine.http.outbox.EventTypes.TimeoutExpired;
import static com.github.thxmasj.statemachine.http.outbox.HttpOutboxRequest.toHttpRequest;

import com.github.thxmasj.statemachine.BasicEventType;
import com.github.thxmasj.statemachine.DataType;
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
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import reactor.core.publisher.Mono;

public final class AtLeastOnce<I> implements HttpOutboxRequest<I> {

  private final String name;
  private final UUID id;
  private final Shape<I, ?, ?, ?> shape;

  private record Shape<I, RQ, RS, S>(
      DataType<I> inputDataType,
      DataType<RQ> requestPayloadType,
      DataType<RS> responsePayloadType,
      Function<TransitionContext<I>, Mono<TypedHttpRequest<RQ>>> messageCreator,
      BiFunction<TransitionContext<Void>, TypedHttpRequest<RQ>, TypedHttpRequest<RQ>> repeatMessageCreator,
      ForwardAction forward,
      Duration inflightTimeout,
      com.github.thxmasj.statemachine.EntityModel processModel,
      Callback<TypedHttpResponse<RS>, S> onSuccess,
      Function<HttpResponseMessage, Validated<RS>> contentParser,
      BiFunction<TypedHttpRequest<RQ>, HttpResponseMessage, Validated<RS>> requestAwareContentParser,
      Predicate<TypedHttpResponse<RS>> isAccepted,
      Predicate<TypedHttpResponse<RS>> isFailureTransient,
      Predicate<Tuple2<HttpResponseMessage, String>> isRejectedByInvalidResponse,
      Predicate<Tuple2<HttpResponseMessage, String>> isFailureByInvalidResponseTransient,
      Predicate<RetryContext> isAttemptAvailable,
      Function<RetryContext, Duration> backoffAlgorithm,
      Function<I, UUID> batchIdExtractor
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

    EventType<I, Tuple2<TypedHttpRequest<RQ>, UUID>> requestDispatched() {
      return BasicEventType.of(
          "Request dispatched",
          UUID.fromString("736f1f23-dba8-467e-a503-a9e2c516197e"),
          inputDataType,
          DataType.tuple(HttpDataType.forRequest(requestPayloadType), DataType.uuid())
      );
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
  public EventType<I, ?> requestDispatched() {
    return shape.requestDispatched();
  }


  @Override
  public DataType<I> inputDataType() {
    return shape.inputDataType();
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
    return request(false, shape, log);
  }

  public boolean scheduledRedispatch(EventLog log) {
    return log.exists(shape.attemptsAvailable());
  }

  public TypedHttpRequest<?> redispatchedRequest(EventLog log) {
    return redispatchedRequest(shape, log);
  }

  private <RQ> TypedHttpRequest<RQ> redispatchedRequest(Shape<I, RQ, ?, ?> shape, EventLog log) {
    return log.oneIfExists(shape.requestRedispatched()).orElse(null);
  }

  public TypedHttpRequest<?> request(boolean optional, EventLog log) {
    return request(optional, shape, log);
  }

  private <RQ> TypedHttpRequest<RQ> request(boolean optional, Shape<I, RQ, ?, ?> shape, EventLog log) {
    return optional ?
        log.oneIfExists(shape.requestRedispatched())
            .orElse(log.oneIfExists(shape.requestDispatched()).map(d -> d.t1()).orElse(null)) :
        log.one(shape.requestDispatched()).t1();
  }

  @Override
  public TypedHttpResponse<?> response(EventLog log) {
    return response(shape, log);
  }

  private <RS> TypedHttpResponse<RS> response(Shape<I, ?, RS, ?> shape, EventLog log) {
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

  public <S, RQ, RS> AtLeastOnce(
      String name,
      UUID id,
      DataType<I> inputDataType,
      DataType<RQ> requestPayloadType,
      DataType<RS> responsePayloadType,
      Function<TransitionContext<I>, Mono<TypedHttpRequest<RQ>>> messageCreator,
      BiFunction<TransitionContext<Void>, TypedHttpRequest<RQ>, TypedHttpRequest<RQ>> repeatMessageCreator,
      HttpClient forwarder,
      Duration inflightTimeout,
      com.github.thxmasj.statemachine.EntityModel processModel,
      Callback<TypedHttpResponse<RS>, S> onSuccess,
      Function<HttpResponseMessage, Validated<RS>> contentParser,
      BiFunction<TypedHttpRequest<RQ>, HttpResponseMessage, Validated<RS>> requestAwareContentParser,
      Predicate<TypedHttpResponse<RS>> isAccepted,
      Predicate<TypedHttpResponse<RS>> isFailureTransient,
      Predicate<Tuple2<HttpResponseMessage, String>> isRejectedByInvalidResponse,
      Predicate<Tuple2<HttpResponseMessage, String>> isFailureByInvalidResponseTransient,
      Predicate<RetryContext> isAttemptAvailable,
      Function<RetryContext, Duration> backoffAlgorithm,
      Function<I, UUID> batchIdExtractor
  ) {
    this.name = name;
    this.id = id;
    this.shape = new Shape<>(
        inputDataType,
        requestPayloadType,
        responsePayloadType,
        messageCreator,
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
        backoffAlgorithm,
        batchIdExtractor
    );
  }

  public Map<State, List<TransitionModel<?, ?>>> inflightTransitions() {
    return inflightTransitions(shape);
  }

  private <RQ, RS, S> Map<State, List<TransitionModel<?, ?>>> inflightTransitions(
      Shape<I, RQ, RS, S> shape
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
                            c.log()
                                .last(shape.requestDispatched())
                                .t1(), // NB: Using last as this transition might be a follow-up rollback on AtMostOnce
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
                .assemble(c -> shape.repeatMessageCreator().apply(c, c.log().one(shape.requestDispatched()).t1()))
                .trigger(shape.forward()).with(d -> toHttpRequest(d, shape.requestPayloadType()))
                .output(d -> d)
        ),
        Intermediate, List.of(),
        Dead, List.of(),
        Accepted, List.of()
    );
  }

  private <RQ, RS, S> TransitionModel<TypedHttpResponse<RS>, TypedHttpResponse<RS>> validResponseTransition(
      Shape<I, RQ, RS, S> shape
  ) {
    return onEvent(shape.validResponse()).to(Intermediate)
            .assemble(c -> c.input())
            .when(shape.isAccepted)
            .then(requestAcceptedTransition(shape))
            .otherwise(requestReceivedAndRejectedTransition(shape));
  }

  private <RQ, RS, S> TransitionModel<TypedHttpResponse<RS>, TypedHttpResponse<RS>> requestAcceptedTransition(
      Shape<I, RQ, RS, S> shape
  ) {
    return shape.onSuccess() != null ?
        onEvent(shape.requestAccepted()).to(Accepted)
            .assemble(c -> tuple(c.input(), c.log().one(shape.requestDispatched()).t2()))
            .trigger(shape.onSuccess().eventType())
            .with(d -> shape.onSuccess().dataAdapter().apply(d.t1()))
            .on(shape.processModel())
            .identifiedBy(d -> entityId(d.t2()))
            .output(d -> d.t1().t1()) :
        onEvent(shape.requestAccepted()).to(Accepted)
            .assembleInput()
            .output(d -> d);
  }

  private <RQ, RS, S> TransitionModel<TypedHttpResponse<RS>, TypedHttpResponse<RS>> requestReceivedAndRejectedTransition(
      Shape<I, RQ, RS, S> shape
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

  private <RQ, RS, S> Map<State, List<TransitionModel<?, ?>>> transitions(Shape<I, RQ, RS, S> shape) {
    return HttpOutboxRequest.combine(
        Map.of(
            Begin, List.of(
                shape.batchIdExtractor != null ?
                    onEvent(shape.requestDispatched()).to(InFlight)
                        .assembleReactive(c -> shape.messageCreator().apply(c)
                            .zipWith(Mono.just(tuple(
                                c.triggerEvent(), // process id
                                c.log().entityId(), // my id
                                shape.batchIdExtractor.apply(c.input()) // batch id
                            ))))

                        .trigger(ProcessReferenced)
                        .with(d -> d.getT2().t1().entityId())
                        .on(Index)
                        .identifiedBy(d -> newEntityId(d.getT2().t2().value()))

                        .trigger(BatchReferenced)
                        .with(d -> d.t1().getT2().t3())
                        .on(Index)
                        .identifiedBy(d -> entityId(d.t1().getT2().t2().value()))

                        .trigger(shape.forward)
                        .with(d -> toHttpRequest(d.t1().t1().getT1(), shape.requestPayloadType))

                        .trigger(TimeoutExpired)
                        .after(_ -> shape.inflightTimeout)

                        .output(d -> tuple(d.t1().t1().getT1(), d.t1().t1().getT2().t1().entityId())) :
                    onEvent(shape.requestDispatched()).to(InFlight)
                        .assembleReactive(c -> shape.messageCreator().apply(c)
                            .zipWith(Mono.just(tuple(
                                c.triggerEvent(), // process id
                                c.log().entityId() // my id
                            ))))

                        .trigger(ProcessReferenced)
                        .with(d -> d.getT2().t1().entityId())
                        .on(Index)
                        .identifiedBy(d -> newEntityId(d.getT2().t2().value()))

                        .trigger(shape.forward)
                        .with(d -> toHttpRequest(d.t1().getT1(), shape.requestPayloadType))

                        .trigger(TimeoutExpired)
                        .after(_ -> shape.inflightTimeout)

                        .output(d -> tuple(d.t1().getT1(), d.t1().getT2().t1().entityId()))
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
