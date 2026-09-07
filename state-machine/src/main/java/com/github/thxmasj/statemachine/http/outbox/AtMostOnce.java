package com.github.thxmasj.statemachine.http.outbox;

import static com.github.thxmasj.statemachine.EntitySelector.entityIdFromSession;
import static com.github.thxmasj.statemachine.TransitionModelBuilder.WithEvent.onEvent;
import static com.github.thxmasj.statemachine.Tuples.tuple;
import static com.github.thxmasj.statemachine.http.outbox.AtMostOnce.States.Begin;
import static com.github.thxmasj.statemachine.http.outbox.AtMostOnce.States.Delivered;
import static com.github.thxmasj.statemachine.http.outbox.AtMostOnce.States.Failed;
import static com.github.thxmasj.statemachine.http.outbox.AtMostOnce.States.InFlight;
import static com.github.thxmasj.statemachine.http.outbox.AtMostOnce.States.Intermediate;
import static com.github.thxmasj.statemachine.http.outbox.AtMostOnce.States.Unknown;
import static com.github.thxmasj.statemachine.http.outbox.EventTypes.ConnectionDropped;
import static com.github.thxmasj.statemachine.http.outbox.EventTypes.ConnectionFailed;
import static com.github.thxmasj.statemachine.http.outbox.EventTypes.ResponseReceived;
import static com.github.thxmasj.statemachine.http.outbox.EventTypes.TimeoutExpired;

import com.github.thxmasj.statemachine.Action;
import com.github.thxmasj.statemachine.BasicEventType;
import com.github.thxmasj.statemachine.EntityModel;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import reactor.core.publisher.Mono;

public final class AtMostOnce<I, RI> implements HttpOutboxRequest<I> {

  private final String name;
  private final UUID id;
  private final EventType<I, HttpRequestMessage> requestDispatched;
  private final AtLeastOnce<RI> rollbackModel;

  @Override
  public EventType<I, HttpRequestMessage> requestDispatched() {
    return requestDispatched;
  }

  public EventType<RI, HttpRequestMessage> rollbackDispatched() {
    return rollbackModel != null ? rollbackModel.requestDispatched() : null;
  }

  private final Map<State, List<TransitionModel<?, ?>>> transitions;

  public <T1, T2, T3, T4, T5, T6, R> AtMostOnce(
      String name,
      UUID id,
      Function<TransitionContext<I>, Mono<HttpRequestMessage>> messageCreator,
      HttpClient forwarder,
      Duration inflightTimeout,
      com.github.thxmasj.statemachine.EntityModel processModel,
      Callback<EntityModel, T1> onPeerUnavailable,
      Callback<EntityModel, T2> onMissingResponse,
      Callback<Tuple2<HttpResponseMessage, R>, T3> onSuccess,
      Callback<Tuple2<HttpResponseMessage, R>, T4> onFailure,
      Callback<Tuple2<HttpResponseMessage, String>, T5> onInvalidResponseRejection,
      Callback<Tuple2<HttpResponseMessage, String>, T6> onInvalidResponseUnknown, // Rollback
      Function<HttpResponseMessage, Validated<R>> contentParser,
      Predicate<Tuple2<HttpResponseMessage, R>> isDelivered,
      Predicate<Tuple2<HttpResponseMessage, String>> isRejectedByInvalidResponse,
      AtLeastOnce<RI> rollbackModel
  ) {
    this.name = name;
    this.id = id;
    this.rollbackModel = rollbackModel;
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
        DataType.unknown(),
        DataType.unknown()
    );
    // Intermediate
    EventType<Tuple2<HttpResponseMessage, String>, Tuple2<HttpResponseMessage, String>> invalidResponse = BasicEventType.of(
        "[invalid response]",
        UUID.fromString("3bd020c3-caf8-4a9b-a10c-d818f98a6de7"),
        DataType.unknown(),
        DataType.unknown()
    );
    // Leaf
    EventType<Tuple2<HttpResponseMessage, R>, HttpResponseMessage> requestAccepted = BasicEventType.of(
        "[request accepted]",
        UUID.fromString("0f8fe1c2-2d29-406c-87b5-f9f43a03a54f"),
        DataType.unknown(),
        HttpResponseMessage.class
    );
    // Leaf
    EventType<Tuple2<HttpResponseMessage, R>, HttpResponseMessage> requestReceivedAndRejected = BasicEventType.of(
        "[request rejected]",
        UUID.fromString("5362567f-792e-4f8a-81d6-3b201d44d3f0"),
        DataType.unknown(),
        HttpResponseMessage.class
    );
    // Leaf
    EventType<Tuple2<HttpResponseMessage, String>, HttpResponseMessage> invalidResponseAndRejected = BasicEventType.of(
        "[request rejected]",
        UUID.fromString("402f9bf4-855c-4383-ac54-3375f9d156d1"),
        DataType.unknown(),
        HttpResponseMessage.class
    );
    // Leaf
    EventType<Tuple2<HttpResponseMessage, String>, HttpResponseMessage> invalidResponseAndUnknown = BasicEventType.of(
        "[unknown status]",
        UUID.fromString("ecfb2c9d-178b-4c3e-b09e-c580e09b01b4"),
        DataType.unknown(),
        HttpResponseMessage.class
    );
    Action<HttpRequestMessage> forward = new Action<>() {
      @Override public String name() {return "Forward";}
      @Override
      public Mono<InputEvent<?>> execute(HttpRequestMessage data) {
        return forwarder.exchange(data)
            .<InputEvent<?>>map(response -> new InputEvent<>(ResponseReceived, response))
            .onErrorResume(ConnectException.class, e -> Mono.just(new InputEvent<>(ConnectionFailed, null)));
      }
    };
    List<TransitionModel<?, ?>> inFlightTransitions = new java.util.ArrayList<>();
    if (rollbackModel != null) {
      inFlightTransitions.add(rollbackModel.requestDispatchedTransition());
    }
    inFlightTransitions.addAll(List.of(
        onPeerUnavailable != null ?
            onEvent(ConnectionFailed).to(Failed)
                .assemble(c -> c.log().entityModel())
                .trigger(onPeerUnavailable.eventType())
                .with(onPeerUnavailable.dataAdapter())
                .on(processModel)
                .identifiedBy(entityIdFromSession())
                .output() :
            onEvent(ConnectionFailed).to(Failed).output(),
        onMissingResponse != null ?
            onEvent(ConnectionDropped).to(Unknown)
                .assemble(c -> c.log().entityModel())
                .trigger(onMissingResponse.eventType())
                .with(onMissingResponse.dataAdapter())
                .on(processModel)
                .identifiedBy(entityIdFromSession())
                .output() :
            onEvent(ConnectionDropped).to(Unknown).output(),
        onMissingResponse != null ?
            onEvent(TimeoutExpired).to(Unknown)
                .assemble(c -> c.log().entityModel())
                .trigger(onMissingResponse.eventType())
                .with(onMissingResponse.dataAdapter())
                .on(processModel)
                .identifiedBy(entityIdFromSession())
                .output() :
            onEvent(TimeoutExpired).to(Unknown).output(),
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
                        onFailure != null ?
                            onEvent(requestReceivedAndRejected).to(Failed)
                                .assembleInput()
                                .trigger(onFailure.eventType())
                                .with(onFailure.dataAdapter())
                                .on(processModel)
                                .identifiedBy(entityIdFromSession())
                                .output(d -> d.t1().t1()) :
                            onEvent(requestReceivedAndRejected).to(Failed)
                                .assembleInput()
                                .output(d -> d.t1())
                    ),
                d -> tuple(d.t1(), d.t2().validValue())
            ).otherwise(
                onEvent(invalidResponse).to(Intermediate)
                    .assembleInput()
                    .when(isRejectedByInvalidResponse)
                    .then(
                        onInvalidResponseRejection != null ?
                            onEvent(invalidResponseAndRejected).to(Failed)
                                .assembleInput()
                                .trigger(onInvalidResponseRejection.eventType())
                                .with(onInvalidResponseRejection.dataAdapter())
                                .on(processModel)
                                .identifiedBy(entityIdFromSession())
                                .output(d -> d.t1().t1()) :
                            onEvent(invalidResponseAndRejected).to(Failed)
                                .assembleInput()
                                .output(d -> d.t1())
                    )
                    .otherwise(
                        onInvalidResponseUnknown != null ?
                          onEvent(invalidResponseAndUnknown).to(Unknown)
                              .assembleInput()
                              .trigger(onInvalidResponseUnknown.eventType())
                              .with(onInvalidResponseUnknown.dataAdapter())
                              .on(processModel)
                              .identifiedBy(entityIdFromSession())
                              .output(d -> d.t1().t1()) :
                            onEvent(invalidResponseAndUnknown).to(Unknown)
                                .assembleInput()
                                .output(d -> d.t1())
                    ),
                d -> tuple(d.t1(), d.t2().invalidReason())
            )
    ));
    Map<State, List<TransitionModel<?, ?>>> t = Map.of(
        Begin, List.of(
            onEvent(requestDispatched).to(InFlight)
                .assembleReactive(c -> messageCreator.apply(c).zipWith(Mono.just(c.triggerEvent())))
                .trigger(forward).with(d -> d.getT1())
                .trigger(TimeoutExpired).after(_ -> inflightTimeout)
                .newIdentifier(ProcessReference, d -> d.getT2())
                .output(d -> d.t1().getT1())
        ),
        InFlight, inFlightTransitions,
        Intermediate, List.of(),
        Failed, List.of(),
        Delivered, rollbackModel != null ? List.of(rollbackModel.requestDispatchedTransition()) : List.of(),
        Unknown, rollbackModel != null ? List.of(rollbackModel.requestDispatchedTransition()) : List.of()
    );
    this.transitions = rollbackModel != null ? combine(t, rollbackModel.transitions()) : t;
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

  private static Map<State, List<TransitionModel<?, ?>>> combine(
      Map<State, List<TransitionModel<?, ?>>> m1,
      Map<State, List<TransitionModel<?, ?>>> m2
  ) {
    return Stream.concat(m1.entrySet().stream(), m2.entrySet().stream())
        .collect(Collectors.toMap(
            Map.Entry::getKey,
            Map.Entry::getValue,
            (_, _) -> {
              throw new IllegalArgumentException("TransitionModel maps can't be combined - they use the same State key");
            }
        ));
  }

  enum States implements State {
    Intermediate,
    Begin,
    InFlight,
    Failed,
    Delivered,
    Unknown
  }
}
