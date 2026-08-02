package com.github.thxmasj.statemachine.http.outbox;

import static com.github.thxmasj.statemachine.BuiltinEventTypes.RequestUndelivered;
import static com.github.thxmasj.statemachine.EntitySelector.entityIdFromSession;
import static com.github.thxmasj.statemachine.TransitionModelBuilder.WithEvent.onEvent;
import static com.github.thxmasj.statemachine.http.outbox.HttpOutbox.States.Begin;
import static com.github.thxmasj.statemachine.http.outbox.HttpOutbox.States.Completed;
import static com.github.thxmasj.statemachine.http.outbox.HttpOutbox.States.InFlight;
import static com.github.thxmasj.statemachine.http.outbox.HttpOutbox.States.InFlightGuaranteed;

import com.github.thxmasj.statemachine.Action;
import com.github.thxmasj.statemachine.BasicEventType;
import com.github.thxmasj.statemachine.DelaySpecification;
import com.github.thxmasj.statemachine.EventType;
import com.github.thxmasj.statemachine.GuardedTransition;
import com.github.thxmasj.statemachine.InputEvent;
import com.github.thxmasj.statemachine.State;
import com.github.thxmasj.statemachine.TransitionModelBuilder.TransitionContext;
import com.github.thxmasj.statemachine.TransitionModelBuilder.TransitionModel;
import com.github.thxmasj.statemachine.Validated;
import com.github.thxmasj.statemachine.http.HttpClient;
import com.github.thxmasj.statemachine.http.outbox.HttpOutbox.DeliveryGuarantee.AtLeastOnce;
import com.github.thxmasj.statemachine.http.outbox.HttpOutbox.DeliveryGuarantee.AtMostOnce;
import com.github.thxmasj.statemachine.message.http.HttpRequestMessage;
import com.github.thxmasj.statemachine.message.http.HttpResponseMessage;
import java.net.ConnectException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import reactor.core.publisher.Mono;

public interface HttpOutbox {

  EventType<HttpResponseMessage, HttpResponseMessage> HandleResponse = BasicEventType.of(
      "Handle response",
      UUID.fromString("cd730efa-286d-4e29-b8bf-55df708fe889"),
      HttpResponseMessage.class,
      HttpResponseMessage.class
  );
  EventType<Void, Void> Retry = BasicEventType.of(
      "Retry",
      UUID.fromString("61bd64d6-6b65-453e-8d60-7b4e01c5aa53"),
      Void.class,
      Void.class
  );

  enum States implements State {
    Begin,
    InFlight,
    InFlightGuaranteed {
      @Override
      public Timeout<?> timeout() {
        return new Timeout<>(Duration.ofSeconds(10), Retry, _ -> null);
      }
    },
    Completed
  }

  interface EntityModel<T> extends com.github.thxmasj.statemachine.EntityModel {

    static <T> EntityModel<T> of(String name, UUID id, Class<T> inputType) {
      return new EntityModel<>() {
        @Override
        public EventType<T, HttpRequestMessage> sendRequest() {
          return BasicEventType.of(
              "Send request",
              UUID.fromString("3f22bfef-dcb4-4560-8b88-6b5040433319"),
              inputType,
              HttpRequestMessage.class
          );
        }
        @Override public String name() {return name;}
        @Override public UUID id() {return id;}
      };
    }

    @Override
    default State initialState() {
      return Begin;
    }

    EventType<T, HttpRequestMessage> sendRequest();
  }

  static <I, S, F> Map<State, List<TransitionModel<?, ?>>> transitions(CustomRequest<I, S, F> request) {
    return switch (request.deliveryGuarantee()) {
      case AtMostOnce _ -> Map.of(
          Begin, List.of(
              onEvent(request.outboxModel().sendRequest()).to(InFlight)
                  .assemble(request.messageCreator())
                  .trigger(forward(request)).with(d -> d)
                  .output(d -> d)
          ),
          InFlight, List.of(
              responseTransition(request)
          )
      );
      case AtLeastOnce _ -> Map.of(
          Begin, List.of(
              onEvent(request.outboxModel().sendRequest()).to(InFlightGuaranteed)
                  .assemble(request.messageCreator())
                  .trigger(forward(request)).with(d -> d)
                  .output(d -> d)
          ),
          InFlightGuaranteed, List.of(
              onEvent(Retry).toSelf()
                  .assemble((_, log) -> log.one(HttpRequestMessage.class))
                  .trigger(forward(request)).with(d -> d)
                  .output(),
              responseTransition(request)
          )
      );
    };
  }

  private static <I, S, F> TransitionModel<HttpResponseMessage, HttpResponseMessage> responseTransition(CustomRequest<I, S, F> request) {
    if (request.responseHandler() != null) {
      // SYNC
      var responseHandler = request.responseHandler();
      EventType<ParsedResponse<I>, HttpResponseMessage> HandleValidResponse = BasicEventType.of(
          "Handle valid response",
          UUID.fromString("b19a7b1d-ea28-4ed1-8442-4981f4e58f12"),
          (Class<ParsedResponse<I>>)null,
          HttpResponseMessage.class
      );
      EventType<ParsedResponse<I>, HttpResponseMessage> HandleInvalidResponse = BasicEventType.of(
          "Handle invalid response",
          UUID.fromString("f55d5b0c-ae92-468c-a905-2e845b64b490"),
          (Class<ParsedResponse<I>>)null,
          HttpResponseMessage.class
      );
      return onEvent(HandleResponse).to(Completed)
          .assemble(c -> new ParsedResponse<>(c.input(), responseHandler.contentParser().apply(c.input())))
          .choice(
              List.of(
                  new GuardedTransition<>(
                      responseHandler.successPredicate(),
                      responseHandler.onSuccess() != null ?
                          onEvent(HandleValidResponse).to(Completed)
                              .assembleInput()
                              .trigger(responseHandler.onSuccess().eventType())
                              .with(responseHandler.onSuccess().dataAdapter())
                              .on(responseHandler.processModel())
                              .identifiedBy(entityIdFromSession())
                              .output(d -> d.t1().message()) :
                          onEvent(HandleValidResponse).to(Completed).assembleInput().output(ParsedResponse::message)
                  ),
                  new GuardedTransition<>(
                      responseHandler.transientFailurePredicate().or(responseHandler.permanentFailurePredicate()),
                      responseHandler.onFailure() != null ?
                          onEvent(HandleInvalidResponse).to(Completed)
                              .assembleInput()
                              .trigger(responseHandler.onFailure().eventType())
                              .with(responseHandler.onFailure().dataAdapter())
                              .on(responseHandler.processModel())
                              .identifiedBy(entityIdFromSession())
                              .output(d -> d.t1().message()) :
                          onEvent(HandleInvalidResponse).to(Completed).assembleInput().output(ParsedResponse::message)
                  )
              )
          )
          .otherwise(onEvent(HandleInvalidResponse).toSelf().output());
    } else {
      // ASYNC
      return onEvent(HandleResponse).to(Completed)
          .assembleInput()
          .output(d -> d);
    }
  }

  private static Action<HttpRequestMessage, HttpResponseMessage> forward(CustomRequest<?, ?, ?> request) {
    return new Action<>() {
      @Override
      public String name() {return "Forward";}

      @Override
      public Mono<InputEvent<HttpResponseMessage>> execute(HttpRequestMessage data) {
        return request.httpForwarder().exchange(data)
            .map(response -> new InputEvent<>(HandleResponse, response))
            .onErrorResume(ConnectException.class, e -> Mono.just(new InputEvent<>(HandleResponse, null)));
      }
    };
  }

  record ResponseHandler<T, S, F>(
      Function<HttpResponseMessage, Validated<T>> contentParser,
      Predicate<ParsedResponse<T>> successPredicate,
      Predicate<ParsedResponse<T>> transientFailurePredicate,
      Predicate<ParsedResponse<T>> permanentFailurePredicate,
      Callback<T, S> onSuccess,
      Callback<T, F> onFailure,
      com.github.thxmasj.statemachine.EntityModel processModel
  ) {}

  record Callback<T, S>(
      Class<S> dataType,
      EventType<S, ?> eventType,
      Function<ParsedResponse<T>, S> dataAdapter
  ) {}

  record ParsedResponse<T>(
      HttpResponseMessage message,
      Validated<T> parsedBody
  ) {}

  record CustomRequest<I, S, F>(
      Function<TransitionContext<I>, HttpRequestMessage> messageCreator,
      BiFunction<TransitionContext<Void>, HttpRequestMessage, HttpRequestMessage> repeatedMessageCreator,
      DeliveryGuarantee deliveryGuarantee,
      HttpClient httpForwarder,
      EntityModel<I> outboxModel,
      ResponseHandler<I, S, F> responseHandler
  ) {

    public static <I, S, F> CustomRequest<I, S, F> async(
        Function<TransitionContext<I>, HttpRequestMessage> messageCreator,
        DeliveryGuarantee deliveryGuarantee,
        HttpClient httpForwarder,
        EntityModel<I> outboxModel
    ) {
      return new CustomRequest<>(
          messageCreator,
          (_, requestMessage) -> requestMessage,
          deliveryGuarantee,
          httpForwarder,
          outboxModel,
          null
      );
    }

    public static <I, S, F> CustomRequest<I, S, F> sync(
        Function<TransitionContext<I>, HttpRequestMessage> messageCreator,
        DeliveryGuarantee deliveryGuarantee,
        HttpClient httpForwarder,
        EntityModel<I> outboxModel,
        ResponseHandler<I, S, F> responseHandler
    ) {
      return new CustomRequest<>(
          messageCreator,
          (_, requestMessage) -> requestMessage,
          deliveryGuarantee,
          httpForwarder,
          outboxModel,
          responseHandler
      );
    }

  }

  sealed interface DeliveryGuarantee {

    record AtMostOnce() implements DeliveryGuarantee {}
    record AtLeastOnce(DelaySpecification repeatStrategy) implements DeliveryGuarantee {}

  }

}
