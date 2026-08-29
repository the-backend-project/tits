package com.github.thxmasj.statemachine.http.outbox;

import static com.github.thxmasj.statemachine.EntitySelector.newEntityId;
import static com.github.thxmasj.statemachine.EventTrigger.trigger;
import static com.github.thxmasj.statemachine.TransitionModelBuilder.WithEvent.onEvent;
import static com.github.thxmasj.statemachine.Validated.valid;
import static com.github.thxmasj.statemachine.message.http.HttpRequestMessage.Method.POST;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.github.thxmasj.statemachine.BasicEventType;
import com.github.thxmasj.statemachine.EntityModel;
import com.github.thxmasj.statemachine.EventType;
import com.github.thxmasj.statemachine.Init;
import com.github.thxmasj.statemachine.State;
import com.github.thxmasj.statemachine.StateMachine;
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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

public class HttpOutboxTest {

  enum States implements State {
    Begin,
    WaitingForResponse {@Override public Timeout<?> timeout() {return rollbackAfter(Duration.ofSeconds(2));}},
    Done
  }
  static HttpServer server;

  @BeforeAll
  public static void setup() throws IOException {
    server = Init.httpServer();
  }

  private static HttpRequestMessage requestMessage(String path) {
    return new HttpRequestMessage(
        POST,
        URI.create("http://localhost:" + server.getAddress().getPort() + path)
    );
  }

  @Test
  public void successfulExchange() {
    var model = ProcessModel.create();
    Init.addOkContext(server, model.path());
    StepVerifier.create(model.machine().onEvent(trigger(model.doProcess(), model.process())))
        .assertNext(event -> assertEquals(model.doProcess(), event.type()))
        .assertNext(event -> assertEquals(model.processed(), event.type()))
        .thenCancel().verify();
  }

  @Test
  public void failedExchange() {
    var model = ProcessModel.create();
    Init.addBadRequestContext(server, model.path());
    StepVerifier.create(model.machine().onEvent(trigger(model.doProcess(), model.process())))
        .assertNext(event -> assertEquals(model.doProcess(), event.type()))
        .assertNext(event -> assertEquals(model.failed(), event.type()))
        .thenCancel().verify();
  }

  record ProcessModel(
      EntityModel process,
      EventType<Void, Void> doProcess,
      EventType<Void, Void> processed,
      EventType<Void, Void> failed,
      StateMachine machine,
      String path
  ) {
    static ProcessModel create() {
      EntityModel process = EntityModel.of("Process", UUID.randomUUID(), States.Begin);
      EventType<Void, Void> doProcess = BasicEventType.of("Do process", UUID.randomUUID());
      EventType<Void, Void> processed = BasicEventType.of("Processed", UUID.randomUUID());
      EventType<Void, Void> failed = BasicEventType.of("Failed", UUID.randomUUID());
      String path = "/" + UUID.randomUUID();
      HttpOutbox<Void> Exchange = new AtMostOnce<>(
          "Exchange",
          UUID.fromString("a3778c63-144f-4748-9e8b-cc10ee20db3f"),
          Void.class,
          _ -> requestMessage(path),
          new NettyHttpClient(new NettyHttpClientBuilder().build()),
          process,
          null,
          null,
          new Callback<>(processed, _ -> null),
          new Callback<>(failed, _ -> null),
          new Callback<>(failed, _ -> null),
          new Callback<>(failed, _ -> null),
          _ -> valid(null), // contentParser
          r -> r.t1().statusCode() >= 200 && r.t1().statusCode() <= 299, // successPredicate
          r -> r.t1().statusCode() >= 400 && r.t1().statusCode() <= 499, // invalidResponseRejectionPredicate
          new AtLeastOnce<>(
              "ExchangeRollback",
              UUID.fromString("fd4959b4-5c14-4b7a-8ea5-b559b94f803c"),
              Void.class,
              _ -> requestMessage("/rollback"),
              (_, o) -> o,
              new NettyHttpClient(new NettyHttpClientBuilder().build()),
              null, // processModel
              null, // onSuccess
              null, // onFailure
              _ -> valid(null), // contentParser
              r -> r.message().statusCode() >= 200 && r.message().statusCode() <= 299,
              r -> r.message().statusCode() >= 500 && r.message().statusCode() <= 599, // transientFailurePredicate
              r -> r.message().statusCode() >= 400 && r.message().statusCode() <= 499 // permanentFailurePredicate
          )
      );

      Map<State, List<TransitionModel<?, ?>>> processTransitions = Map.of(
          States.Begin, List.of(
              onEvent(doProcess).to(States.WaitingForResponse)
                  .trigger(Exchange.requestDispatched()).on(Exchange).identifiedBy(newEntityId())
                  .output()
          ),
          States.WaitingForResponse, List.of(
              onEvent(processed).to(States.Done)
                  .output(),
              onEvent(failed).to(States.Done)
                  .output()
          )
      );

      var machine = Init.stateMachine(
          process,
          processTransitions,
          List.of(),
          List.of(Exchange)
      );
      return new ProcessModel(process, doProcess, processed, failed, machine, path);
    }

  }

}
