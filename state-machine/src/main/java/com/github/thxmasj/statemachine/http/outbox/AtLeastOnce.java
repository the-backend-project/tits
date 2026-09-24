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

import com.github.thxmasj.statemachine.Action;
import com.github.thxmasj.statemachine.BasicEventType;
import com.github.thxmasj.statemachine.DataType;
import com.github.thxmasj.statemachine.EventType;
import com.github.thxmasj.statemachine.InputEvent;
import com.github.thxmasj.statemachine.State;
import com.github.thxmasj.statemachine.TransitionModelBuilder.TransitionContext;
import com.github.thxmasj.statemachine.TransitionModelBuilder.TransitionModel;
import com.github.thxmasj.statemachine.Tuples.Tuple2;
import com.github.thxmasj.statemachine.Validated;
import com.github.thxmasj.statemachine.http.HttpClient;
import com.github.thxmasj.statemachine.http.HttpDataType;
import com.github.thxmasj.statemachine.message.http.HttpRequestMessage;
import com.github.thxmasj.statemachine.message.http.HttpResponseMessage;
import com.github.thxmasj.statemachine.message.http.TypedHttpRequest;
import com.github.thxmasj.statemachine.message.http.TypedHttpResponse;
import java.net.ConnectException;
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
  private final EventType<I, ?> requestDispatched;
  private final TransitionModel<I, ?> externalRequestDispatchedTransition;
  private final Map<State, List<TransitionModel<?, ?>>> transitions;
  private final Map<State, List<TransitionModel<?, ?>>> inflightTransitions;

  @Override
  public EventType<I, ?> requestDispatched() {
    return requestDispatched;
  }

  public TransitionModel<I, ?> requestDispatchedTransition() {
    return externalRequestDispatchedTransition;
  }

  public Map<State, List<TransitionModel<?, ?>>> inflightTransitions() {
    return inflightTransitions;
  }

  public record RetryContext(
      ZonedDateTime enqueueTime,
      ZonedDateTime now,
      long attemptNumber
  ) {}

  public <S, RQ, RS> AtLeastOnce(
      String name,
      UUID id,
      DataType<RQ> requestPayloadType,
      DataType<RS> responsePayloadType,
      Function<TransitionContext<I>, Mono<TypedHttpRequest<RQ>>> messageCreator,
      BiFunction<TransitionContext<Void>, TypedHttpRequest<RQ>, TypedHttpRequest<RQ>> repeatMessageCreator,
      HttpClient forwarder,
      Duration inflightTimeout,
      com.github.thxmasj.statemachine.EntityModel processModel,
      Callback<TypedHttpResponse<RS>, S> onSuccess,
      Function<HttpResponseMessage, Validated<RS>> contentParser,
      Predicate<TypedHttpResponse<RS>> isAccepted,
      Predicate<TypedHttpResponse<RS>> isFailureTransient,
      Predicate<Tuple2<HttpResponseMessage, String>> isRejectedByInvalidResponse,
      Predicate<Tuple2<HttpResponseMessage, String>> isFailureByInvalidResponseTransient,
      Predicate<RetryContext> isAttemptAvailable,
      Function<RetryContext, Duration> backoffAlgorithm
  ) {
    this.name = name;
    this.id = id;
    EventType<I, Tuple2<TypedHttpRequest<RQ>, UUID>> requestDispatched = BasicEventType.of(
        "Request dispatched ALO",
        UUID.fromString("3f22bfef-dcb4-4560-8b88-6b5040433319"),
        DataType.unknown(),
        DataType.tuple(HttpDataType.forRequest(requestPayloadType), DataType.uuid())
    );
    this.requestDispatched = requestDispatched;
    // Intermediate
    EventType<TypedHttpResponse<RS>, TypedHttpResponse<RS>> validResponse = BasicEventType.of(
        "[valid response]",
        UUID.fromString("c9fcf4d6-95f8-418f-aa6b-c3d987d3a3c3"),
        DataType.unknown(),
        DataType.unknown()
    );
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
    EventType<TypedHttpResponse<RS>, TypedHttpResponse<RS>> requestReceivedAndRejected = BasicEventType.of(
        "[request rejected]",
        UUID.fromString("5362567f-792e-4f8a-81d6-3b201d44d3f0"),
        DataType.unknown(),
        HttpDataType.forResponse(responsePayloadType)
    );
    EventType<Void, TypedHttpResponse<RS>> requestReceivedAndRejectedTransiently = BasicEventType.of(
        "[request rejected transiently]",
        UUID.fromString("bcdf93f4-657d-4b26-b165-74279a5ea477"),
        DataType.unknown(),
        HttpDataType.forResponse(responsePayloadType)
    );
    EventType<TypedHttpResponse<RS>, TypedHttpResponse<RS>> requestReceivedAndRejectedPermanently = BasicEventType.of(
        "[request rejected permanently]",
        UUID.fromString("94b1169f-8e09-45d5-bdff-cae9b146db30"),
        DataType.unknown(),
        HttpDataType.forResponse(responsePayloadType)
    );
    EventType<Tuple2<HttpResponseMessage, String>, HttpResponseMessage> invalidResponseAndRejectedPermanently = BasicEventType.of(
        "[invalid response, request rejected permanently]",
        UUID.fromString("94eca92d-a229-4aa9-bc02-48260fa1bbf0"),
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
    EventType<TypedHttpResponse<RS>, TypedHttpResponse<RS>> requestAccepted = BasicEventType.of(
        "[request accepted]",
        UUID.fromString("0f8fe1c2-2d29-406c-87b5-f9f43a03a54f"),
        DataType.unknown(),
        HttpDataType.forResponse(responsePayloadType)
    );
    // Leaf
    EventType<Void, HttpResponseMessage> invalidResponseAndUnknown = BasicEventType.of(
        "[invalid response, unknown status]",
        UUID.fromString("ecfb2c9d-178b-4c3e-b09e-c580e09b01b4"),
        DataType.unknown(),
        HttpDataType.forResponse()
    );
    var attemptsExhausted = BasicEventType.of("Attempts exhausted", UUID.fromString("19a29ca5-6021-41e7-b247-74fd3b8389dd"));
    var attemptsAvailable = BasicEventType.of("Attempts available", UUID.fromString("e6d6331f-5891-4d97-b699-ffe0dcf9d6cb"), DataType.json(RetryContext.class), DataType.none());
    Action<HttpRequestMessage> forward = new Action<>() {
      @Override public String name() {return "Forward";}
      @Override
      public Mono<InputEvent<?>> execute(HttpRequestMessage data) {
        return forwarder.exchange(data)
            .<InputEvent<?>>map(response -> new InputEvent<>(ResponseReceived, response))
            .onErrorResume(ConnectException.class, _ -> Mono.just(new InputEvent<>(ConnectionFailed, null)));
      }
    };
    Function<TransitionContext<Void>, RetryContext> retry = c -> new RetryContext(c.log().created(), c.timestamp(), c.log().count(attemptsAvailable) + 1);
    // NB! This transition is only used by AtMostOnce and does not add the ProcessReference identifier as it will be already added.
    this.externalRequestDispatchedTransition = onEvent(requestDispatched).to(InFlight)
        .assembleReactive(c -> messageCreator.apply(c).zipWith(Mono.just(c.triggerEvent())))
        .trigger(forward).with(d -> toHttpRequest(d.getT1(), requestPayloadType))
        .trigger(TimeoutExpired).after(_ -> inflightTimeout)
        .output(d -> tuple(d.getT1(), d.getT2().entityId()));
    this.inflightTransitions = Map.of(
        InFlight, List.of(
            onEvent(ConnectionFailed).to(Intermediate)
                .assemble(retry)
                .when(isAttemptAvailable)
                .then(
                    onEvent(attemptsAvailable).to(Compensating)
                        .assembleInput()
                        .trigger(TimeoutExpired).after(backoffAlgorithm)
                        .output(),
                    d -> d
                )
                .otherwise(onEvent(attemptsExhausted).to(Dead).output(), _ -> null),
            onEvent(ConnectionDropped).to(Intermediate)
                .assemble(retry)
                .when(isAttemptAvailable)
                .then(
                    onEvent(attemptsAvailable).to(Compensating)
                        .assembleInput()
                        .trigger(TimeoutExpired).after(backoffAlgorithm)
                        .output(),
                    d -> d
                )
                .otherwise(onEvent(attemptsExhausted).to(Dead).output(), _ -> null),
            onEvent(TimeoutExpired).to(Intermediate)
                .assemble(retry)
                .when(isAttemptAvailable)
                .then(
                    onEvent(attemptsAvailable).to(Compensating)
                        .assembleInput()
                        .trigger(TimeoutExpired).after(backoffAlgorithm)
                        .output(),
                    d -> d
                )
                .otherwise(onEvent(attemptsExhausted).to(Dead).output(), _ -> null),
            onEvent(ResponseReceived).to(Intermediate)
                .assemble(c -> tuple(c.input(), contentParser.apply(c.input())))
                .when(d -> d.t2().isValid())
                .then(
                    onEvent(validResponse).to(Intermediate)
                        .assembleInput()
                        .when(isAccepted)
                        .then(
                            onSuccess != null ?
                                onEvent(requestAccepted).to(Accepted)
                                    .assemble(c -> tuple(c.input(), c.log().one(requestDispatched).t2()))
                                    .trigger(onSuccess.eventType())
                                    .with(d -> onSuccess.dataAdapter().apply(d.t1()))
                                    .on(processModel)
                                    .identifiedBy(d -> entityId(d.t2()))
                                    .output(d -> d.t1().t1()) :
                                onEvent(requestAccepted).to(Accepted)
                                    .assembleInput()
                                    .output(d -> d)
                        )
                        .otherwise(
                            onEvent(requestReceivedAndRejected).to(Intermediate)
                                .assembleInput()
                                .when(isFailureTransient)
                                .then(
                                    onEvent(requestReceivedAndRejectedTransiently).to(Intermediate)
                                        .assemble(retry)
                                        .when(isAttemptAvailable)
                                        .then(
                                            onEvent(attemptsAvailable).to(Compensating)
                                                .assembleInput()
                                                .trigger(TimeoutExpired).after(backoffAlgorithm)
                                                .output(),
                                            d -> d
                                        )
                                        .otherwise(onEvent(attemptsExhausted).to(Dead).output(), _ -> null),
                                    _ -> null
                                )
                                .otherwise(
                                    onEvent(requestReceivedAndRejectedPermanently).to(Dead)
                                        .assembleInput()
                                        .output(d -> d)
                                )
                        ),
                    d -> TypedHttpResponse.create(
                        d.t1().statusCode(),
                        d.t1().reasonPhrase(),
                        d.t1().headers(),
                        d.t2().validValue()
                    )
                ).otherwise(
                    onEvent(invalidResponse).to(Intermediate)
                        .assembleInput()
                        .when(isRejectedByInvalidResponse)
                        .then(
                            onEvent(invalidResponseAndRejected).to(Dead)
                                .assembleInput()
                                .when(isFailureByInvalidResponseTransient)
                                .then(
                                    onEvent(invalidResponseAndRejectedTransiently).to(Intermediate)
                                        .assemble(retry)
                                        .when(isAttemptAvailable)
                                        .then(
                                            onEvent(attemptsAvailable).to(Compensating)
                                                .assembleInput()
                                                .trigger(TimeoutExpired).after(backoffAlgorithm)
                                                .output(),
                                            d -> d
                                        )
                                        .otherwise(onEvent(attemptsExhausted).to(Dead).output(), _ -> null),
                                    _ -> null
                                )
                                .otherwise(
                                    onEvent(invalidResponseAndRejectedPermanently).to(Dead)
                                        .assembleInput()
                                        .output(d -> d.t1())
                                )
                        )
                        .otherwise(
                                onEvent(invalidResponseAndUnknown).to(Intermediate)
                                    .assemble(retry)
                                    .when(isAttemptAvailable)
                                    .then(
                                        onEvent(attemptsAvailable).to(Compensating)
                                            .assembleInput()
                                            .trigger(TimeoutExpired).after(backoffAlgorithm)
                                            .output(),
                                        d -> d
                                    )
                                    .otherwise(onEvent(attemptsExhausted).to(Dead).output(), _ -> null),
                            _ -> null
                        ),
                    d -> tuple(d.t1(), d.t2().invalidReason())
                )
        ),
        Compensating, List.of(
            onEvent(TimeoutExpired).to(InFlight)
                .assemble(c -> repeatMessageCreator.apply(c, c.log().one(requestDispatched).t1()))
                .trigger(forward).with(d -> toHttpRequest(d, requestPayloadType))
                .output()
            ),
        Intermediate, List.of(),
        Dead, List.of(),
        Accepted, List.of()
    );
    this.transitions = HttpOutboxRequest.combine(
        Map.of(
            Begin, List.of(
                onEvent(requestDispatched).to(InFlight)
                    .assembleReactive(c -> messageCreator.apply(c).zipWith(Mono.just(tuple(c.triggerEvent(), c.log().entityId(), c.log().entityModel()))))
                    .trigger(Indexed).with(d -> d.getT2().t1().entityId()).on(ProcessReference).identifiedBy(d -> newEntityId(d.getT2().t2().value()))
                    .trigger(forward).with(d -> toHttpRequest(d.t1().getT1(), requestPayloadType))
                    .trigger(TimeoutExpired).after(_ -> inflightTimeout)
                    //.newIdentifier(ProcessReference, d -> d.getT2())
                    .output(d -> tuple(d.t1().getT1(), d.t1().getT2().t1().entityId()))
            )
        ), inflightTransitions
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

  @Override
  public Map<State, List<TransitionModel<?, ?>>> transitions() {
    return transitions;
  }

  enum States implements State {
    InFlight,
    Accepted,
    Compensating,
    Dead
  }

}
