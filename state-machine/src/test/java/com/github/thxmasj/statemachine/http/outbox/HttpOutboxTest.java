package com.github.thxmasj.statemachine.http.outbox;

import static com.github.thxmasj.statemachine.BuiltinEventTypes.Rollback;
import static com.github.thxmasj.statemachine.EntitySelector.newEntityId;
import static com.github.thxmasj.statemachine.EventTrigger.trigger;
import static com.github.thxmasj.statemachine.TransitionModelBuilder.WithEvent.onEvent;
import static com.github.thxmasj.statemachine.TransitionModelBuilder.assemble;
import static com.github.thxmasj.statemachine.message.http.HttpRequestMessage.Method.POST;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.github.thxmasj.statemachine.BasicEventType;
import com.github.thxmasj.statemachine.BuiltinEventTypes;
import com.github.thxmasj.statemachine.EntityModel;
import com.github.thxmasj.statemachine.Event;
import com.github.thxmasj.statemachine.EventTrigger;
import com.github.thxmasj.statemachine.EventTrigger.EventSpec;
import com.github.thxmasj.statemachine.EventType;
import com.github.thxmasj.statemachine.Init;
import com.github.thxmasj.statemachine.State;
import com.github.thxmasj.statemachine.TransitionModelBuilder.TransitionModel;
import com.github.thxmasj.statemachine.http.NettyHttpClient;
import com.github.thxmasj.statemachine.http.NettyHttpClientBuilder;
import com.github.thxmasj.statemachine.http.outbox.HttpOutbox.CustomRequest;
import com.github.thxmasj.statemachine.http.outbox.HttpOutbox.DeliveryGuarantee.AtMostOnce;
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
import reactor.test.StepVerifier;

public class HttpOutboxTest {

  enum States implements State {
    Begin,
    WaitingForResponse {@Override public Timeout<?> timeout() {return rollbackAfter(Duration.ofSeconds(2));}},
    Done
  }
  static HttpOutbox.EntityModel<Void> Exchange = HttpOutbox.EntityModel.of("Exchange", UUID.fromString("4e46d568-5c7f-4a3b-ae4c-e6ef5eab72d2"), Void.class);
  static EntityModel model = EntityModel.of("Process", UUID.fromString("5b1e3415-cde6-478c-a329-4af7e1a32c1f"), States.Begin);
  static EventType<Void, Void> requestEvent = BasicEventType.of("Request", UUID.fromString("599c52aa-f89c-41a4-8836-e7aba133eac9"));
  static EventType<Void, Void> responseEvent = BasicEventType.of("Response", UUID.fromString("f4b3e70d-13a1-4804-a8a9-6dc803e8e99e"));
  static HttpServer server;

  @BeforeAll
  public static void setup() throws IOException {
    server = Init.httpServer();
    Init.addDelayContext(server, "/delay4", Duration.ofSeconds(4));
    Init.addOkContext(server, "/empty-response");
  }

  private static HttpRequestMessage requestMessage(String path) {
    return new HttpRequestMessage(
        POST,
        URI.create("http://localhost:" + server.getAddress().getPort() + path)
    );
  }

  @Test
  public void successfulExchange() {
    EntityModel process = EntityModel.of("Process", UUID.randomUUID(), States.Begin);
    EventType<Void, Void> doProcess = BasicEventType.of("Do process", UUID.randomUUID());
    EventType<Void, Void> processed = BasicEventType.of("Processed", UUID.randomUUID());
    Map<State, List<TransitionModel<?, ?>>> processTransitions = Map.of(
        States.Begin, List.of(
            onEvent(doProcess).to(States.WaitingForResponse)
                .trigger(Exchange.sendRequest()).on(Exchange).identifiedBy(newEntityId())
                .output()
        ),
        States.WaitingForResponse, List.of(
            onEvent(processed).to(States.Done)
                .output()
        )
    );
    String path = "/" + UUID.randomUUID();
    var machine = Init.stateMachine(
        process,
        processTransitions,
        List.of(),
        List.of(CustomRequest.sync(
            _ -> requestMessage(path),
            new AtMostOnce(),
            processed,
            process,
            new NettyHttpClient(new NettyHttpClientBuilder().build()),
            Exchange
        ))
    );
    Init.addOkContext(server, path);
    StepVerifier.create(machine.onEvent(trigger(doProcess, process)))
        .assertNext(event -> assertEquals(doProcess, event.type()))
        .assertNext(event -> assertEquals(processed, event.type()))
        .thenCancel().verify();
  }

  @Test
  public void invalidResponseIsRolledBack() {
    EntityModel process = EntityModel.of("Process", UUID.randomUUID(), States.Begin);
    EventType<Void, Void> doProcess = BasicEventType.of("Do process", UUID.randomUUID());
    EventType<Void, Void> processed = BasicEventType.of("Processed", UUID.randomUUID());
    Map<State, List<TransitionModel<?, ?>>> processTransitions = Map.of(
        States.Begin, List.of(
            onEvent(doProcess).to(States.WaitingForResponse)
                .trigger(Exchange.sendRequest()).on(Exchange).identifiedBy(newEntityId())
                .output()
        ),
        States.WaitingForResponse, List.of(
            onEvent(processed).to(States.Done)
                .output()
        )
    );
    String path = "/" + UUID.randomUUID();
    var machine = Init.stateMachine(
        process,
        processTransitions,
        List.of(),
        List.of(CustomRequest.sync(
            _ -> requestMessage(path),
            new AtMostOnce(),
            processed,
            process,
            new NettyHttpClient(new NettyHttpClientBuilder().build()),
            Exchange
        ))
    );
    Init.addBadRequestContext(server, path);
    StepVerifier.create(machine.onEvent(trigger(doProcess, process)))
        .assertNext(event -> assertEquals(doProcess, event.type()))
        .assertNext(event -> assertEquals(processed, event.type()))
        .thenCancel().verify();
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
                //.trigger(request("/delay4")).with(d -> d).to(otherService)
                .trigger(Exchange.sendRequest()).on(Exchange).identifiedBy(newEntityId())
                //.responseValidator(validator(Status.Ok, new InputEvent<>(responseEvent, null)))
                .output()
        ),
        States.WaitingForResponse, List.of(onEvent(responseEvent).to(States.Done).output())
    );
    var machine = Init.stateMachine(
        model,
        processTransitions,
        List.of(),
        List.of(CustomRequest.async(
            _ -> requestMessage("/delay4"),
            new AtMostOnce(),
            new NettyHttpClient(new NettyHttpClientBuilder().build()),
            Exchange
        ))
    );
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
            onEvent(requestEvent).to(States.WaitingForResponse)
                //.trigger(request("/empty-response")).with(d -> d).to(otherService)
                .trigger(Exchange.sendRequest()).on(Exchange).identifiedBy(newEntityId())
                //.responseValidator(validator(Status.PermanentError, new InputEvent<>(Rollback, new Data(-1, 1, "Malformed response"))))
                .output()
        ),
        States.WaitingForResponse, List.of(onEvent(responseEvent).to(States.Done).output())
    );
    var machine = Init.stateMachine(
        model,
        processTransitions,
        List.of(),
        List.of(CustomRequest.async(
            _ -> requestMessage("/empty-response"),
            new AtMostOnce(),
            new NettyHttpClient(new NettyHttpClientBuilder().build()),
            Exchange
        ))
    );
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
            onEvent(requestEvent).to(States.WaitingForResponse)
//                .trigger(request("/empty-response"))
//                .with(d -> d).to(otherService)
//                .responseValidator(validator(Status.PermanentError, new InputEvent<>(Rollback, new Data(-1, 1, "Malformed response"))))
                .trigger(Exchange.sendRequest()).on(Exchange).identifiedBy(newEntityId())
                .reversible(
                    assemble((_, _) -> "Hello, world!")
                        .trigger(BuiltinEventTypes.Status).on(model).identifiedBy(newEntityId())
                )
                .output()
        ),
        States.WaitingForResponse, List.of(onEvent(responseEvent).to(States.Done).output())
    );
    var machine = Init.stateMachine(
        model,
        processTransitions,
        List.of(),
        List.of(CustomRequest.async(
            _ -> requestMessage("/empty-response"),
            new AtMostOnce(),
            new NettyHttpClient(new NettyHttpClientBuilder().build()),
            Exchange
        ))
    );
    List<Event<?>> results = machine.onEvent(new EventTrigger<>(new EventSpec<>(requestEvent, Function.identity()), List.of(newEntityId()), model, false))
        .take(2)
        .collectList()
        .block(Duration.ofSeconds(5));
    assertEquals(requestEvent, results.get(0).type());
    assertEquals(Rollback, results.get(1).type());
  }

}
