package com.github.thxmasj.statemachine.http.outbox;

import static com.github.thxmasj.statemachine.EntitySelector.entityIdFromSession;
import static com.github.thxmasj.statemachine.TransitionModelBuilder.WithEvent.onEvent;
import static com.github.thxmasj.statemachine.Tuples.tuple;
import static com.github.thxmasj.statemachine.http.outbox.AtLeastOnce.States.Compensating;
import static com.github.thxmasj.statemachine.http.outbox.AtLeastOnce.States.Begin;
import static com.github.thxmasj.statemachine.http.outbox.AtLeastOnce.States.Dead;
import static com.github.thxmasj.statemachine.http.outbox.AtLeastOnce.States.Delivered;
import static com.github.thxmasj.statemachine.http.outbox.AtLeastOnce.States.InFlight;
import static com.github.thxmasj.statemachine.http.outbox.AtLeastOnce.States.Intermediate;
import static com.github.thxmasj.statemachine.http.outbox.EventTypes.ConnectionDropped;
import static com.github.thxmasj.statemachine.http.outbox.EventTypes.ConnectionFailed;
import static com.github.thxmasj.statemachine.http.outbox.EventTypes.ResponseReceived;
import static com.github.thxmasj.statemachine.http.outbox.EventTypes.TimeoutExpired;

import com.github.thxmasj.statemachine.Action;
import com.github.thxmasj.statemachine.BasicEventType;
import com.github.thxmasj.statemachine.EventType;
import com.github.thxmasj.statemachine.EventType.DataType;
import com.github.thxmasj.statemachine.InputEvent;
import com.github.thxmasj.statemachine.State;
import com.github.thxmasj.statemachine.TransitionModelBuilder.TransitionContext;
import com.github.thxmasj.statemachine.TransitionModelBuilder.TransitionModel;
import com.github.thxmasj.statemachine.Tuples.Tuple2;
import com.github.thxmasj.statemachine.Validated;
import com.github.thxmasj.statemachine.http.HttpClient;
import com.github.thxmasj.statemachine.message.http.HttpRequestMessage;
import com.github.thxmasj.statemachine.message.http.HttpResponseMessage;
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
  private final EventType<I, HttpRequestMessage> requestDispatched;
  private final TransitionModel<I, HttpRequestMessage> requestDispatchedTransition;


  @Override
  public EventType<I, HttpRequestMessage> requestDispatched() {
    return requestDispatched;
  }

  public TransitionModel<I, HttpRequestMessage> requestDispatchedTransition() {
    return requestDispatchedTransition;
  }

  private final Map<State, List<TransitionModel<?, ?>>> transitions;


  public record RetryContext(
      ZonedDateTime enqueueTime,
      ZonedDateTime now,
      int attemptNumber
  ) {}

  public <S, R> AtLeastOnce(
      String name,
      UUID id,
      Function<TransitionContext<I>, Mono<HttpRequestMessage>> messageCreator,
      BiFunction<TransitionContext<Void>, HttpRequestMessage, HttpRequestMessage> repeatMessageCreator,
      HttpClient forwarder,
      Duration inflightTimeout,
      com.github.thxmasj.statemachine.EntityModel processModel,
      Callback<Tuple2<HttpResponseMessage, R>, S> onSuccess,
      Function<HttpResponseMessage, Validated<R>> contentParser,
      Predicate<Tuple2<HttpResponseMessage, R>> isDelivered, // 2
      Predicate<Tuple2<HttpResponseMessage, R>> isFailureTransient, // 3
      Predicate<Tuple2<HttpResponseMessage, String>> isRejectedByInvalidResponse, // 4
      Predicate<Tuple2<HttpResponseMessage, String>> isFailureByInvalidResponseTransient, // 5
      Predicate<RetryContext> isAttemptAvailable, // 6, 7, 8, 9
      Function<RetryContext, Duration> backoffAlgorithm
  ) {
    this.name = name;
    this.id = id;
    this.requestDispatched = BasicEventType.of(
        "Request dispatched",
        UUID.fromString("3f22bfef-dcb4-4560-8b88-6b5040433319"),
        DataType.unknown(),
        HttpRequestMessage.class
    );
    // Intermediate
    EventType<Tuple2<HttpResponseMessage, R>, Tuple2<HttpResponseMessage, R>> validResponse = BasicEventType.of(
        "[valid response]",
        UUID.fromString("c9fcf4d6-95f8-418f-aa6b-c3d987d3a3c3"),
        null
    );
    // Intermediate
    EventType<Tuple2<HttpResponseMessage, String>, Tuple2<HttpResponseMessage, String>> invalidResponse = BasicEventType.of(
        "[invalid response]",
        UUID.fromString("3bd020c3-caf8-4a9b-a10c-d818f98a6de7"),
        null
    );
    // Intermediate
    EventType<Tuple2<HttpResponseMessage, String>, HttpResponseMessage> invalidResponseAndRejected = BasicEventType.of(
        "[invalid response, request rejected]",
        UUID.fromString("402f9bf4-855c-4383-ac54-3375f9d156d1"),
        DataType.unknown(),
        HttpResponseMessage.class
    );
    EventType<Tuple2<HttpResponseMessage, R>, HttpResponseMessage> requestReceivedAndRejected = BasicEventType.of(
        "[request rejected]",
        UUID.fromString("5362567f-792e-4f8a-81d6-3b201d44d3f0"),
        DataType.unknown(),
        HttpResponseMessage.class
    );
    EventType<Tuple2<HttpResponseMessage, R>, HttpResponseMessage> requestReceivedAndRejectedTransiently = BasicEventType.of(
        "[request rejected transiently]",
        UUID.fromString("bcdf93f4-657d-4b26-b165-74279a5ea477"),
        DataType.unknown(),
        HttpResponseMessage.class
    );
    EventType<Tuple2<HttpResponseMessage, R>, HttpResponseMessage> requestReceivedAndRejectedPermanently = BasicEventType.of(
        "[request rejected permanently]",
        UUID.fromString("94b1169f-8e09-45d5-bdff-cae9b146db30"),
        DataType.unknown(),
        HttpResponseMessage.class
    );
    EventType<Tuple2<HttpResponseMessage, String>, HttpResponseMessage> invalidResponseAndRejectedPermanently = BasicEventType.of(
        "[invalid response, request rejected permanently]",
        UUID.fromString("94eca92d-a229-4aa9-bc02-48260fa1bbf0"),
        DataType.unknown(),
        HttpResponseMessage.class
    );
    EventType<Tuple2<HttpResponseMessage, String>, HttpResponseMessage> invalidResponseAndRejectedTransiently = BasicEventType.of(
        "[invalid response, request rejected transiently]",
        UUID.fromString("9f121370-4b8d-4a08-a7ef-30555c1e1f48"),
        DataType.unknown(),
        HttpResponseMessage.class
    );
    // Leaf
    EventType<Tuple2<HttpResponseMessage, R>, HttpResponseMessage> requestAccepted = BasicEventType.of(
        "[request accepted]",
        UUID.fromString("0f8fe1c2-2d29-406c-87b5-f9f43a03a54f"),
        DataType.unknown(),
        HttpResponseMessage.class
    );
    // Leaf
    EventType<Tuple2<HttpResponseMessage, String>, HttpResponseMessage> invalidResponseAndUnknown = BasicEventType.of(
        "[invalid response, unknown status]",
        UUID.fromString("ecfb2c9d-178b-4c3e-b09e-c580e09b01b4"),
        DataType.unknown(),
        HttpResponseMessage.class
    );
    var attemptsExhausted = BasicEventType.of("Attempts exhausted", UUID.fromString("19a29ca5-6021-41e7-b247-74fd3b8389dd"));
    var attemptsAvailable = BasicEventType.of("Attempts available", UUID.fromString("e6d6331f-5891-4d97-b699-ffe0dcf9d6cb"), RetryContext.class, Void.class);
    Action<HttpRequestMessage> forward = new Action<>() {
      @Override public String name() {return "Forward";}
      @Override
      public Mono<InputEvent<?>> execute(HttpRequestMessage data) {
        return forwarder.exchange(data)
            .<InputEvent<?>>map(response -> new InputEvent<>(ResponseReceived, response))
            .onErrorResume(ConnectException.class, _ -> Mono.just(new InputEvent<>(ConnectionFailed, null)));
      }
    };
    // NB! This transition is only used by AtMostOnce and does not add the ProcessReference identifier as it is always added.
    this.requestDispatchedTransition = onEvent(requestDispatched).to(InFlight)
        .assembleReactive(messageCreator)
        .trigger(forward).with(d -> d)
        .trigger(TimeoutExpired).after(_ -> inflightTimeout)
        .output(d -> d);
    this.transitions = Map.of(
        Begin, List.of(
            onEvent(requestDispatched).to(InFlight)
                .assembleReactive(c -> messageCreator.apply(c).zipWith(Mono.just(c.triggerEvent())))
                .trigger(forward).with(d -> d.getT1())
                .trigger(TimeoutExpired).after(_ -> inflightTimeout)
                .newIdentifier(ProcessReference, d -> d.getT2())
                .output(d -> d.t1().getT1())
        ),
        InFlight, List.of(
            onEvent(ConnectionFailed).to(Intermediate)
                .assemble(c -> new RetryContext(c.log().created(), c.timestamp(), c.log().lastEventNumber()))
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
                .assemble(c -> new RetryContext(c.log().created(), c.timestamp(), c.log().lastEventNumber()))
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
                .assemble(c -> new RetryContext(c.log().created(), c.timestamp(), c.log().lastEventNumber()))
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
                        .when(isDelivered)
                        .then(
                            onSuccess != null ?
                                onEvent(requestAccepted).to(Delivered)
                                    .assembleInput()
                                    .trigger(onSuccess.eventType())
                                    .with(onSuccess.dataAdapter())
                                    .on(processModel)
                                    .identifiedBy(entityIdFromSession())
                                    .output(d -> d.t1().t1()) :
                                onEvent(requestAccepted).to(Delivered)
                                    .assembleInput()
                                    .output(d -> d.t1())
                        )
                        .otherwise(
                            onEvent(requestReceivedAndRejected).to(Intermediate)
                                .assembleInput()
                                .when(isFailureTransient)
                                .then(
                                    onEvent(requestReceivedAndRejectedTransiently).to(Intermediate)
                                        .assemble(c -> new RetryContext(c.log().created(), c.timestamp(), c.log().lastEventNumber()))
                                        .when(isAttemptAvailable)
                                        .then(
                                            onEvent(attemptsAvailable).to(Compensating)
                                                .assembleInput()
                                                .trigger(TimeoutExpired).after(backoffAlgorithm)
                                                .output(),
                                            d -> d
                                        )
                                        .otherwise(onEvent(attemptsExhausted).to(Dead).output(), _ -> null)
                                )
                                .otherwise(
                                    onEvent(requestReceivedAndRejectedPermanently).to(Dead)
                                        .assembleInput()
                                        .output(d -> d.t1())
                                )
                        ),
                    d -> tuple(d.t1(), d.t2().validValue())
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
                                        .assemble(c -> new RetryContext(c.log().created(), c.timestamp(), c.log().lastEventNumber()))
                                        .when(isAttemptAvailable)
                                        .then(
                                            onEvent(attemptsAvailable).to(Compensating)
                                                .assembleInput()
                                                .trigger(TimeoutExpired).after(backoffAlgorithm)
                                                .output(),
                                            d -> d
                                        )
                                        .otherwise(onEvent(attemptsExhausted).to(Dead).output(), _ -> null)
                                )
                                .otherwise(
                                    onEvent(invalidResponseAndRejectedPermanently).to(Dead)
                                        .assembleInput()
                                        .output(d -> d.t1())
                                )
                        )
                        .otherwise(
                                onEvent(invalidResponseAndUnknown).to(Intermediate)
                                    .assemble(c -> new RetryContext(c.log().created(), c.timestamp(), c.log().lastEventNumber()))
                                    .when(isAttemptAvailable)
                                    .then(
                                        onEvent(attemptsAvailable).to(Compensating)
                                            .assembleInput()
                                            .trigger(TimeoutExpired).after(backoffAlgorithm)
                                            .output(),
                                        d -> d
                                    )
                                    .otherwise(onEvent(attemptsExhausted).to(Dead).output(), _ -> null)
                        ),
                    d -> tuple(d.t1(), d.t2().invalidReason())
                )
        ),
        Compensating, List.of(
            onEvent(TimeoutExpired).to(InFlight)
                .assemble(c -> repeatMessageCreator.apply(c, c.log().one(requestDispatched)))
                .trigger(forward).with(d -> d)
                .output()
            )
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
  public State initialState() {
    return States.Begin;
  }

  @Override
  public Map<State, List<TransitionModel<?, ?>>> transitions() {
    return transitions;
  }

  enum States implements State {
    Intermediate,
    Begin,
    InFlight,
    Delivered,
    Compensating,
    Dead
  }

}
