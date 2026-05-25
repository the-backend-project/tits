package com.github.thxmasj.statemachine;

import static com.github.thxmasj.statemachine.BuiltinEventTypes.Rollback;
import static com.github.thxmasj.statemachine.EntitySelector.newEntityId;
import static com.github.thxmasj.statemachine.TransitionModelBuilder.WithEvent.onEvent;
import static com.github.thxmasj.statemachine.TransitionModelBuilder.assemble;
import static com.github.thxmasj.statemachine.message.http.HttpRequestMessage.Method.POST;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.github.thxmasj.statemachine.BasicEventType.Rollback.Data;
import com.github.thxmasj.statemachine.EventTrigger.EventSpec;
import com.github.thxmasj.statemachine.IncomingResponseValidator.Result;
import com.github.thxmasj.statemachine.IncomingResponseValidator.Result.Status;
import com.github.thxmasj.statemachine.TransitionModelBuilder.TransitionModel;
import com.github.thxmasj.statemachine.http.NettyHttpClient;
import com.github.thxmasj.statemachine.http.NettyHttpClientBuilder;
import com.github.thxmasj.statemachine.message.http.HttpRequestMessage;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

public class HttpOutboxTest {

  private static EntityModel entityModel(String name, UUID id, State initialState) {
    return new EntityModel() {
      @Override public String name() {return name;}
      @Override public UUID id() {return id;}
      @Override public State initialState() {return initialState;}
    };
  }

  enum States implements State {
    Begin,
    WaitingForResponse {@Override public Timeout timeout() {return rollbackAfter(Duration.ofSeconds(2));}},
    Done
  }
  static OutboxQueue otherService = OutboxQueue.of("A queue", UUID.fromString("cc8d2271-cc02-4b4d-8bb0-9c0e0fa2d4e7"));
  static EntityModel model = entityModel("Process", UUID.fromString("5b1e3415-cde6-478c-a329-4af7e1a32c1f"), States.Begin);
  static EventType<Void, Void> requestEvent = BasicEventType.of("Request", UUID.fromString("599c52aa-f89c-41a4-8836-e7aba133eac9"));
  static EventType<Void, Void> responseEvent = BasicEventType.of("Response", UUID.fromString("f4b3e70d-13a1-4804-a8a9-6dc803e8e99e"));
  static HttpServer server;

  @BeforeAll
  public static void setup() throws IOException {
    server = Init.httpServer();
    Init.addDelayContext(server, "/delay4", Duration.ofSeconds(4));
    Init.addOkContext(server, "/empty-response");
  }

  private static OutgoingRequestCreator<Void> request(String path) {
    return new OutgoingRequestCreator<>() {
      @Override
      public HttpRequestMessage create(Void data, Context context) {
        return new HttpRequestMessage(
            POST,
            URI.create("http://localhost:" + server.getAddress().getPort() + path)
        );
      }

      @Override
      public UUID id() {return UUID.fromString("a1054f09-6cce-4e0f-af37-f90b1d97a312");}
    };
  }

  private IncomingResponseValidator<Void> validator(Status status, InputEvent<?> responseEvent) {
    return (_, _, _, _) -> Mono.just(new Result(status, null, responseEvent));
  }

  /// Verifies behavior when a response arrives after the waiting state times out.
  /// * Event log should immediately have the triggered event, which also is the event that triggers the request.
  /// * Immediately after the state timeout the event log should also have a rollback event. (TODO)
  /// * No event for the response should be recorded after the server responds. (TODO)
  /// * The outbox queue should be empty. (TODO)
  @Test
  public void responseArrivesAfterStateTimeout() {
    Map<State, List<TransitionModel<?, ?>>> processTransitions = Map.of(
        States.Begin, List.of(
            onEvent(requestEvent).to(States.WaitingForResponse)
                .trigger(request("/delay4")).with(d -> d).to(otherService)
                .responseValidator(validator(Status.Ok, new InputEvent<>(responseEvent, null)))
                .output()
        ),
        States.WaitingForResponse, List.of(onEvent(responseEvent).to(States.Done).output())
    );
    var machine = Init.stateMachine(model, processTransitions, List.of(), _ -> new NettyHttpClient(new NettyHttpClientBuilder().build()));
    List<Event<?>> results = machine.onEvent(new EventTrigger<>(new EventSpec<>(requestEvent, Function.identity()), List.of(newEntityId()), model, false))
        //.take(2)
        .take(1)
        .collectList()
        .block(Duration.ofSeconds(5));
    assertEquals(requestEvent, results.get(0).type());
    //assertEquals(Rollback, results.get(1).type());
  }

  @Test
  public void corruptedResponse() {
    Map<State, List<TransitionModel<?, ?>>> processTransitions = Map.of(
        States.Begin, List.of(
            onEvent(requestEvent).to(States.WaitingForResponse).trigger(request("/empty-response"))
                .with(d -> d).to(otherService)
                .responseValidator(validator(Status.PermanentError, new InputEvent<>(Rollback, new Data(-1, "Malformed response"))))
                .output()
        ),
        States.WaitingForResponse, List.of(onEvent(responseEvent).to(States.Done).output())
    );
    var machine = Init.stateMachine(model, processTransitions, List.of(), _ -> new NettyHttpClient(new NettyHttpClientBuilder().build()));
    List<Event<?>> results = machine.onEvent(new EventTrigger<>(new EventSpec<>(requestEvent, Function.identity()), List.of(newEntityId()), model, false))
        .take(2)
        .collectList()
        .block(Duration.ofSeconds(5));
    assertEquals(requestEvent, results.get(0).type());
    assertEquals(Rollback, results.get(1).type());
  }

  @Test
  public void rollbackSideEffects() {
    Map<State, List<TransitionModel<?, ?>>> processTransitions = Map.of(
        States.Begin, List.of(
            onEvent(requestEvent).to(States.WaitingForResponse).trigger(request("/empty-response"))
                .with(d -> d).to(otherService)
                .responseValidator(validator(Status.PermanentError, new InputEvent<>(Rollback, new Data(-1, "Malformed response"))))
                .reversible(
                    assemble((_, _) -> "Hello, world!")
                        .trigger(BuiltinEventTypes.Status).on(model).identifiedBy(newEntityId())
                )
                .output()
        ),
        States.WaitingForResponse, List.of(onEvent(responseEvent).to(States.Done).output())
    );
    var machine = Init.stateMachine(model, processTransitions, List.of(), _ -> new NettyHttpClient(new NettyHttpClientBuilder().build()));
    List<Event<?>> results = machine.onEvent(new EventTrigger<>(new EventSpec<>(requestEvent, Function.identity()), List.of(newEntityId()), model, false))
        .take(2)
        .collectList()
        .block(Duration.ofSeconds(5));
    assertEquals(requestEvent, results.get(0).type());
    assertEquals(Rollback, results.get(1).type());
  }

}
