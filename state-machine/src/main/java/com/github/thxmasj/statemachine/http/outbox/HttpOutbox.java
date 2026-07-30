package com.github.thxmasj.statemachine.http.outbox;

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
import com.github.thxmasj.statemachine.InputEvent;
import com.github.thxmasj.statemachine.State;
import com.github.thxmasj.statemachine.TransitionModelBuilder.TransitionContext;
import com.github.thxmasj.statemachine.TransitionModelBuilder.TransitionModel;
import com.github.thxmasj.statemachine.Tuples.Tuple2;
import com.github.thxmasj.statemachine.http.HttpClient;
import com.github.thxmasj.statemachine.http.outbox.HttpOutbox.DeliveryGuarantee.AtLeastOnce;
import com.github.thxmasj.statemachine.http.outbox.HttpOutbox.DeliveryGuarantee.AtMostOnce;
import com.github.thxmasj.statemachine.message.http.HttpRequestMessage;
import com.github.thxmasj.statemachine.message.http.HttpResponseMessage;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;
import reactor.core.publisher.Mono;

public interface HttpOutbox {

  EventType<HttpResponseMessage, HttpResponseMessage> Response = BasicEventType.of(
      "Response",
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

  static <I, O> Map<State, List<TransitionModel<?, ?>>> transitions(CustomRequest<I, O> request) {
    return switch (request.deliveryGuarantee()) {
      case AtLeastOnce _ -> Map.of(
          Begin, List.of(
              onEvent(request.outboxModel().sendRequest()).to(InFlight)
                  .assemble(request.messageCreator())
                  .trigger(forwardAction(request)).with(d -> d)
                  .output(d -> d)
          ),
          InFlight, List.of(responseTransition(request))
      );
      case AtMostOnce _ -> Map.of(
          Begin, List.of(
              onEvent(request.outboxModel().sendRequest()).to(InFlightGuaranteed)
                  .assemble(request.messageCreator())
                  .trigger(forwardAction(request)).with(d -> d)
                  .output(d -> d)
          ),
          InFlightGuaranteed, List.of(
              onEvent(Retry).toSelf()
                  .assemble((_, log) -> log.one(HttpRequestMessage.class))
                  .trigger(forwardAction(request)).with(d -> d)
                  .output(),
              responseTransition(request)
          )
      );
    };
  }

  private static <I, O> TransitionModel<HttpResponseMessage, HttpResponseMessage> responseTransition(CustomRequest<I, O> request) {
    return request.responseEventType() != null ?
        onEvent(Response).to(Completed)
            .assembleInput()
            .trigger(request.responseEventType()).on(request.responseModel()).identifiedBy(entityIdFromSession())
            .output(Tuple2::t1) :
        onEvent(Response).to(Completed)
            .assembleInput()
            .output(d -> d);
  }

  private static Action<HttpRequestMessage, HttpResponseMessage> forwardAction(CustomRequest<?, ?> request) {
    return new Action<>() {
      @Override
      public String name() {return "Forward";}

      @Override
      public Mono<InputEvent<HttpResponseMessage>> execute(HttpRequestMessage data) {
        return request.httpForwarder().exchange(data)
            .map(response -> new InputEvent<>(Response, response));
      }
    };
  }

  record CustomRequest<I, O>(
      Function<TransitionContext<I>, HttpRequestMessage> messageCreator,
      BiFunction<TransitionContext<Void>, HttpRequestMessage, HttpRequestMessage> repeatedMessageCreator,
      DeliveryGuarantee deliveryGuarantee,
      EventType<O, ?> responseEventType,
      com.github.thxmasj.statemachine.EntityModel responseModel,
      HttpClient httpForwarder,
      EntityModel<I> outboxModel
  ) {

    public static <I, O> CustomRequest<I, O> async(
        Function<TransitionContext<I>, HttpRequestMessage> messageCreator,
        DeliveryGuarantee deliveryGuarantee,
        HttpClient httpForwarder,
        EntityModel<I> outboxModel
    ) {
      return new CustomRequest<>(
          messageCreator,
          (_, requestMessage) -> requestMessage,
          deliveryGuarantee,
          null,
          null,
          httpForwarder,
          outboxModel
      );
    }

    public static <I, O> CustomRequest<I, O> sync(
        Function<TransitionContext<I>, HttpRequestMessage> messageCreator,
        DeliveryGuarantee deliveryGuarantee,
        EventType<O, ?> responseEventType,
        com.github.thxmasj.statemachine.EntityModel responseModel,
        HttpClient httpForwarder,
        EntityModel<I> outboxModel
    ) {
      return new CustomRequest<>(
          messageCreator,
          (_, requestMessage) -> requestMessage,
          deliveryGuarantee,
          responseEventType,
          responseModel,
          httpForwarder,
          outboxModel
      );
    }

  }

  sealed interface DeliveryGuarantee {

    record AtMostOnce() implements DeliveryGuarantee {}
    record AtLeastOnce(DelaySpecification repeatStrategy) implements DeliveryGuarantee {}

  }

}
