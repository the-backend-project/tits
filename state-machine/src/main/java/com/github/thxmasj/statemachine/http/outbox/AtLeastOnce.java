package com.github.thxmasj.statemachine.http.outbox;

import static com.github.thxmasj.statemachine.EntitySelector.entityIdFromSession;
import static com.github.thxmasj.statemachine.TransitionModelBuilder.WithEvent.onEvent;
import static com.github.thxmasj.statemachine.Tuples.tuple;
import static com.github.thxmasj.statemachine.http.outbox.EventTypes.HandleResponse;
import static com.github.thxmasj.statemachine.http.outbox.EventTypes.Retry;
import static com.github.thxmasj.statemachine.http.outbox.States.Begin;
import static com.github.thxmasj.statemachine.http.outbox.States.Completed;
import static com.github.thxmasj.statemachine.http.outbox.States.InFlightGuaranteed;

import com.fasterxml.jackson.core.type.TypeReference;
import com.github.thxmasj.statemachine.Action;
import com.github.thxmasj.statemachine.BasicEventType;
import com.github.thxmasj.statemachine.EventType;
import com.github.thxmasj.statemachine.EventType.DataType;
import com.github.thxmasj.statemachine.GuardedTransition;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import reactor.core.publisher.Mono;

public final class AtLeastOnce<I, R, S, F> implements HttpOutbox<I> {

  private final String name;
  private final UUID id;
  private final EventType<I, Tuple2<I, HttpRequestMessage>> sendRequest;

  @Override
  public EventType<I, Tuple2<I, HttpRequestMessage>> sendRequest() {
    return sendRequest;
  }

  private final Map<State, List<TransitionModel<?, ?>>> transitions;

  public AtLeastOnce(
      String name,
      UUID id,
      Class<I> inputDataType,
      Function<TransitionContext<I>, HttpRequestMessage> messageCreator,
      BiFunction<TransitionContext<Void>, HttpRequestMessage, HttpRequestMessage> repeatMessageCreator,
      HttpClient httpForwarder,
      com.github.thxmasj.statemachine.EntityModel processModel,
      Callback<ParsedResponse<R>, S> onSuccess,
      Callback<ParsedResponse<R>, F> onFailure,
      Function<HttpResponseMessage, Validated<R>> contentParser,
      Predicate<ParsedResponse<R>> successPredicate,
      Predicate<ParsedResponse<R>> transientFailurePredicate,
      Predicate<ParsedResponse<R>> permanentFailurePredicate
  ) {
    this.name = name;
    this.id = id;
    this.sendRequest = BasicEventType.of(
        "Send request",
        UUID.fromString("3f22bfef-dcb4-4560-8b88-6b5040433319"),
        inputDataType,
        new DataType<>(new TypeReference<>(){}, inputDataType, HttpRequestMessage.class)
    );
    EventType<String, String> HandleUndeliveredRequest = BasicEventType.of(
        "Handle undelivered request",
        UUID.fromString("14be9085-1f7c-427c-9963-2c72cdc0888f"),
        (Class<String>)null,
        String.class
    );
    EventType<ParsedResponse<R>, HttpResponseMessage> HandleValidResponse = BasicEventType.of(
        "Handle valid response",
        UUID.fromString("b19a7b1d-ea28-4ed1-8442-4981f4e58f12"),
        (Class<ParsedResponse<R>>) null,
        HttpResponseMessage.class
    );
    EventType<ParsedResponse<R>, HttpResponseMessage> HandleInvalidResponse = BasicEventType.of(
        "Handle invalid response",
        UUID.fromString("f55d5b0c-ae92-468c-a905-2e845b64b490"),
        (Class<ParsedResponse<R>>) null,
        HttpResponseMessage.class
    );
    Action<HttpRequestMessage> forward = new Action<>() {
      @Override public String name() {return "Forward";}
      @Override
      public Mono<InputEvent<?>> execute(HttpRequestMessage data) {
        return httpForwarder.exchange(data)
            .<InputEvent<?>>map(response -> new InputEvent<>(HandleResponse, response))
            .onErrorResume(ConnectException.class, e -> Mono.just(new InputEvent<>(HandleUndeliveredRequest, e.getMessage())));
      }
    };
    this.transitions = Map.of(
        Begin, List.of(
            onEvent(sendRequest).to(InFlightGuaranteed)
                .assemble(c -> tuple(c.input(), messageCreator.apply(c)))
                .trigger(forward).with(Tuple2::t2)
                .output(d -> d)
        ),
        InFlightGuaranteed, List.of(
            onEvent(Retry).toSelf()
                .assemble(c -> repeatMessageCreator.apply(c, c.log().one(sendRequest).t2()))
                .trigger(forward).with(d -> d)
                .output(),
            onEvent(HandleResponse).to(Completed)
                .assemble(c -> new ParsedResponse<>(c.input(), contentParser.apply(c.input())))
                .choice(
                    List.of(
                        new GuardedTransition<>(
                            successPredicate,
                            onSuccess != null ?
                                onEvent(HandleValidResponse).to(Completed)
                                    .assembleInput()
                                    .trigger(onSuccess.eventType())
                                    .with(onSuccess.dataAdapter())
                                    .on(processModel)
                                    .identifiedBy(entityIdFromSession())
                                    .output(d -> d.t1().message()) :
                                onEvent(HandleValidResponse).to(Completed).assembleInput().output(ParsedResponse::message)
                        ),
                        new GuardedTransition<>(
                            transientFailurePredicate.or(permanentFailurePredicate),
                            onFailure != null ?
                                onEvent(HandleInvalidResponse).to(Completed)
                                    .assembleInput()
                                    .trigger(onFailure.eventType())
                                    .with(onFailure.dataAdapter())
                                    .on(processModel)
                                    .identifiedBy(entityIdFromSession())
                                    .output(d -> d.t1().message()) :
                                onEvent(HandleInvalidResponse).to(Completed).assembleInput().output(ParsedResponse::message)
                        )
                    )
                )
                .otherwise(
                    onEvent(HandleInvalidResponse).to(Completed)
                        .assembleInput()
                        .output(ParsedResponse::message)
                )
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
  public Map<State, List<TransitionModel<?, ?>>> transitions() {
    return transitions;
  }

}
