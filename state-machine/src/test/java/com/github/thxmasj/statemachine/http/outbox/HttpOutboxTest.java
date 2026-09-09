package com.github.thxmasj.statemachine.http.outbox;

import static com.github.thxmasj.statemachine.BuiltinEventTypes.Rollback;
import static com.github.thxmasj.statemachine.EntitySelector.entityId;
import static com.github.thxmasj.statemachine.EntitySelector.newEntityId;
import static com.github.thxmasj.statemachine.EventTrigger.trigger;
import static com.github.thxmasj.statemachine.TransitionModelBuilder.WithEvent.onEvent;
import static com.github.thxmasj.statemachine.TransitionModelBuilder.assemble;
import static com.github.thxmasj.statemachine.Validated.invalid;
import static com.github.thxmasj.statemachine.Validated.valid;
import static com.github.thxmasj.statemachine.http.outbox.EventTypes.InvalidResponse;
import static com.github.thxmasj.statemachine.http.outbox.EventTypes.TimeoutExpired;
import static com.github.thxmasj.statemachine.message.http.HttpRequestMessage.Method.POST;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.github.thxmasj.statemachine.BasicEventType;
import com.github.thxmasj.statemachine.BasicEventType.Rollback.Data;
import com.github.thxmasj.statemachine.DelaySpecification;
import com.github.thxmasj.statemachine.EntityModel;
import com.github.thxmasj.statemachine.Event;
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

  @Test
  public void unknownExchange() {
    unknownExchange(ProcessModel.create());
  }

  private Event<?> unknownExchange(ProcessModel model) {
    Init.addInternalServerErrorContext(server, model.path());
    var events = model.machine().onEvent(trigger(model.doProcess(), model.process()))
        .take(2)
        .collectList()
        .block();
    assertNotNull(events);
    assertEquals(model.doProcess(), events.get(0).type());
    assertEquals(model.unknown(), events.get(1).type());
    return events.get(1);
  }

  @Test
  public void rollbackOfUnknownExchange() {
    var model = ProcessModel.create();
    Init.addOkContext(server, "/rollback");
    var unknown = unknownExchange(model);
    var rollback = model.machine().onEvent(
        trigger(Rollback, model.process(), unknown.entityId()),
        new Data(0, unknown.eventNumber(), "Technical")
    ).blockFirst();
    assertNotNull(rollback);
    assertEquals(Rollback, rollback.type());
  }

  @Test
  public void successfulExchangeWithoutRollback() {
    var model = ProcessModelWithoutRollback.create();
    Init.addOkContext(server, model.path());
    StepVerifier.create(model.machine().onEvent(trigger(model.doProcess(), model.process())))
        .assertNext(event -> assertEquals(model.doProcess(), event.type()))
        .assertNext(event -> assertEquals(model.processed(), event.type()))
        .thenCancel().verify();
  }

  @Test
  public void failedExchangeWithoutRollback() {
    var model = ProcessModelWithoutRollback.create();
    Init.addBadRequestContext(server, model.path());
    StepVerifier.create(model.machine().onEvent(trigger(model.doProcess(), model.process())))
        .assertNext(event -> assertEquals(model.doProcess(), event.type()))
        .assertNext(event -> assertEquals(model.failed(), event.type()))
        .thenCancel().verify();
  }

  @Test
  public void defaultInvalidResponseRejection() {
    var model = ProcessModelWithDefaultInvalidResponseRejection.create();
    Init.addBadRequestContext(server, model.path());
    StepVerifier.create(model.machine().onEvent(trigger(model.doProcess(), model.process())))
        .assertNext(event -> assertEquals(model.doProcess(), event.type()))
        .assertNext(event -> {
          assertEquals(InvalidResponse, event.type());
          assertEquals("Invalid response", event.data());
        })
        .thenCancel().verify();
  }

  @Test
  public void defaultInvalidResponseUnknown() {
    var model = ProcessModelWithDefaultInvalidResponseUnknown.create();
    Init.addBadRequestContext(server, model.path());
    StepVerifier.create(model.machine().onEvent(trigger(model.doProcess(), model.process())))
        .assertNext(event -> assertEquals(model.doProcess(), event.type()))
        .assertNext(event -> {
          assertEquals(InvalidResponse, event.type());
          assertEquals("Invalid response", event.data());
        })
        .thenCancel().verify();
  }

  @Test
  public void defaultMissingResponse() {
    var model = ProcessModelWithDefaultMissingResponse.create();
    var inFlightTransitions = model.exchange().transitions().get(AtMostOnce.States.InFlight);
    var timeoutTransition = inFlightTransitions.stream()
        .filter(t -> t.eventType().equals(TimeoutExpired))
        .findFirst()
        .orElseThrow();
    assertEquals(1, timeoutTransition.triggers().size());
    assertEquals(TimeoutExpired, timeoutTransition.triggers().get(0).eventSpec().eventType());
  }

  record ProcessModel(
      EntityModel process,
      EventType<Void, UUID> doProcess,
      EventType<Void, Void> processed,
      EventType<Void, Void> failed,
      EventType<Void, Void> missingResponse,
      EventType<Void, Void> unknown,
      StateMachine machine,
      String path
  ) {
    static ProcessModel create() {
      EntityModel process = EntityModel.of("Process", UUID.fromString("dda0cc10-3356-4522-8527-ca4f7006c566"), States.Begin);
      EventType<Void, UUID> doProcess = BasicEventType.of("Do process", UUID.fromString("dbcf351c-b50e-4789-80e9-f52e2be789cd"), Void.class, UUID.class);
      EventType<Void, Void> processed = BasicEventType.of("Processed", UUID.fromString("433d18b2-8429-4615-aeb0-5de2240413ea"));
      EventType<Void, Void> failed = BasicEventType.of("Failed", UUID.fromString("0f4b6aea-a9c2-4b33-b315-844a4e32e45b"));
      EventType<Void, Void> missingResponse = BasicEventType.of("Missing response", UUID.fromString("7cd9c1f1-1ce0-4249-a709-5e11ea2f6404"));
      EventType<Void, Void> unknown = BasicEventType.of("Unknown", UUID.fromString("46cf31dd-56ed-41cb-b2ca-501930a23e44"));
      String path = "/" + UUID.randomUUID();
      AtMostOnce<Void, Void> Exchange = HttpOutboxRequest.atMostOnce()
          .name("Exchange")
          .id(UUID.fromString("a3778c63-144f-4748-9e8b-cc10ee20db3f"))
          .<Void>messageCreator(_ -> requestMessage(path))
          .forwarder(new NettyHttpClient(new NettyHttpClientBuilder().build()))
          .processModel(process)
          .onPeerUnavailable(failed)
          .onMissingResponse(missingResponse)
          .onInvalidResponseRejection(failed)
          .onInvalidResponseUnknown(unknown)
          .contentParser(message -> message.statusCode() == 200 && message.body() == null ? valid((Void) null) : invalid("Invalid response"))
          .onSuccess(processed)
          .onFailure(failed)
          .isDelivered(r -> r.t1().statusCode() >= 200 && r.t1().statusCode() <= 299)
          .isRejectedByInvalidResponse(r -> r.t1().statusCode() >= 400 && r.t1().statusCode() <= 499)
          .rollbackModel(HttpOutboxRequest.atLeastOnce()
              .name("ExchangeRollback")
              .id(UUID.fromString("fd4959b4-5c14-4b7a-8ea5-b559b94f803c"))
              .<Void>messageCreator(_ -> requestMessage("/rollback"))
              .repeatMessageCreator((_, o) -> o)
              .forwarder(new NettyHttpClient(new NettyHttpClientBuilder().build()))
              .contentParser(_ -> valid(null))
              .isDelivered(r -> r.t1().statusCode() >= 200 && r.t1().statusCode() <= 299)
              .isFailureTransient(r -> r.t1().statusCode() >= 500 && r.t1().statusCode() <= 599)
              .isRejectedByInvalidResponse(r -> r.t1().statusCode() >= 400 && r.t1().statusCode() <= 499)
              .isFailureByInvalidResponseTransient(r -> r.t1().statusCode() >= 400 && r.t1().statusCode() <= 499)
              .isAttemptAvailable(c -> Duration.between(c.enqueueTime(), c.now()).compareTo(Duration.ofHours(5)) < 0)
              .backoffAlgorithm(c -> new DelaySpecification(Duration.ofSeconds(10), Duration.ofMinutes(10), Duration.ofHours(5), 1.5).calculateDelay(c.attemptNumber()))
              .build()
          )
          .build();

      Map<State, List<TransitionModel<?, ?>>> processTransitions = Map.of(
          States.Begin, List.of(
              onEvent(doProcess).to(States.WaitingForResponse)
                  .trigger(Exchange.requestDispatched()).on(Exchange).identifiedBy(newEntityId())
                  .reversible(
                      assemble((log, _) -> log.one(doProcess))
                          .trigger(Exchange.rollbackDispatched()).on(Exchange).identifiedBy(d -> entityId(d))
                  )
                  .output(d -> d.t2().accepted().event().entityId())
          ),
          States.WaitingForResponse, List.of(
              onEvent(processed).to(States.Done).output(),
              onEvent(failed).to(States.Done).output(),
              onEvent(unknown).to(States.Done).output()
          ),
          States.Done, List.of()
      );

      var machine = Init.stateMachine(
          process,
          processTransitions,
          List.of(),
          List.of(Exchange)
      );
      return new ProcessModel(process, doProcess, processed, failed, missingResponse, unknown, machine, path);
    }

  }

  record ProcessModelWithoutRollback(
      EntityModel process,
      EventType<Void, Void> doProcess,
      EventType<Void, Void> processed,
      EventType<Void, Void> failed,
      StateMachine machine,
      String path
  ) {
    static ProcessModelWithoutRollback create() {
      EntityModel process = EntityModel.of("Process", UUID.randomUUID(), States.Begin);
      EventType<Void, Void> doProcess = BasicEventType.of("Do process", UUID.randomUUID());
      EventType<Void, Void> processed = BasicEventType.of("Processed", UUID.randomUUID());
      EventType<Void, Void> failed = BasicEventType.of("Failed", UUID.randomUUID());
      String path = "/" + UUID.randomUUID();
      HttpOutboxRequest<Void> Exchange = HttpOutboxRequest.atMostOnce()
          .name("ExchangeWithoutRollback")
          .id(UUID.fromString("a3778c63-144f-4748-9e8b-cc10ee20db3f"))
          .<Void>messageCreator(_ -> requestMessage(path))
          .forwarder(new NettyHttpClient(new NettyHttpClientBuilder().build()))
          .processModel(process)
          .onPeerUnavailable(failed)
          .onMissingResponse(failed)
          .onInvalidResponseRejection(failed)
          .onInvalidResponseUnknown(failed)
          .contentParser(_ -> valid((Void) null))
          .onSuccess(processed)
          .onFailure(failed)
          .isDelivered(r -> r.t1().statusCode() >= 200 && r.t1().statusCode() <= 299)
          .isRejectedByInvalidResponse(r -> r.t1().statusCode() >= 400 && r.t1().statusCode() <= 499)
          .build();

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
      return new ProcessModelWithoutRollback(process, doProcess, processed, failed, machine, path);
    }

  }

  record ProcessModelWithDefaultInvalidResponseRejection(
      EntityModel process,
      EventType<Void, Void> doProcess,
      StateMachine machine,
      String path
  ) {
    static ProcessModelWithDefaultInvalidResponseRejection create() {
      EntityModel process = EntityModel.of("Process", UUID.randomUUID(), States.Begin);
      EventType<Void, Void> doProcess = BasicEventType.of("Do process", UUID.randomUUID());
      String path = "/" + UUID.randomUUID();
      HttpOutboxRequest<Void> Exchange = HttpOutboxRequest.atMostOnce()
          .name("ExchangeWithDefaultInvalidResponse")
          .id(UUID.fromString("a3778c63-144f-4748-9e8b-cc10ee20db3f"))
          .<Void>messageCreator(_ -> requestMessage(path))
          .forwarder(new NettyHttpClient(new NettyHttpClientBuilder().build()))
          .processModel(process)
          .contentParser(_ -> invalid("Invalid response"))
          .isDelivered(_ -> false)
          .isRejectedByInvalidResponse(_ -> true)
          .build();

      Map<State, List<TransitionModel<?, ?>>> processTransitions = Map.of(
          States.Begin, List.of(
              onEvent(doProcess).to(States.WaitingForResponse)
                  .trigger(Exchange.requestDispatched()).on(Exchange).identifiedBy(newEntityId())
                  .output()
          ),
          States.WaitingForResponse, List.of(
              onEvent(InvalidResponse).to(States.Done)
                  .assembleInput()
                  .output(d -> d)
          ),
          States.Done, List.of()
      );

      var machine = Init.stateMachine(
          process,
          processTransitions,
          List.of(),
          List.of(Exchange)
      );
      return new ProcessModelWithDefaultInvalidResponseRejection(process, doProcess, machine, path);
    }

  }

  record ProcessModelWithDefaultInvalidResponseUnknown(
      EntityModel process,
      EventType<Void, Void> doProcess,
      StateMachine machine,
      String path
  ) {
    static ProcessModelWithDefaultInvalidResponseUnknown create() {
      EntityModel process = EntityModel.of("Process", UUID.randomUUID(), States.Begin);
      EventType<Void, Void> doProcess = BasicEventType.of("Do process", UUID.randomUUID());
      String path = "/" + UUID.randomUUID();
      HttpOutboxRequest<Void> Exchange = HttpOutboxRequest.atMostOnce()
          .name("ExchangeWithDefaultInvalidResponseUnknown")
          .id(UUID.fromString("a3778c63-144f-4748-9e8b-cc10ee20db3f"))
          .<Void>messageCreator(_ -> requestMessage(path))
          .forwarder(new NettyHttpClient(new NettyHttpClientBuilder().build()))
          .processModel(process)
          .contentParser(_ -> invalid("Invalid response"))
          .isDelivered(_ -> false)
          .isRejectedByInvalidResponse(_ -> false)
          .build();

      Map<State, List<TransitionModel<?, ?>>> processTransitions = Map.of(
          States.Begin, List.of(
              onEvent(doProcess).to(States.WaitingForResponse)
                  .trigger(Exchange.requestDispatched()).on(Exchange).identifiedBy(newEntityId())
                  .output()
          ),
          States.WaitingForResponse, List.of(
              onEvent(InvalidResponse).to(States.Done)
                  .assembleInput()
                  .output(d -> d)
          ),
          States.Done, List.of()
      );

      var machine = Init.stateMachine(
          process,
          processTransitions,
          List.of(),
          List.of(Exchange)
      );
      return new ProcessModelWithDefaultInvalidResponseUnknown(process, doProcess, machine, path);
    }

  }

  record ProcessModelWithDefaultMissingResponse(
      EntityModel process,
      HttpOutboxRequest<Void> exchange
  ) {
    static ProcessModelWithDefaultMissingResponse create() {
      EntityModel process = EntityModel.of("Process", UUID.randomUUID(), States.Begin);
      String path = "/" + UUID.randomUUID();
      HttpOutboxRequest<Void> exchange = HttpOutboxRequest.atMostOnce()
          .name("ExchangeWithDefaultMissingResponse")
          .id(UUID.fromString("a3778c63-144f-4748-9e8b-cc10ee20db3f"))
          .<Void>messageCreator(_ -> requestMessage(path))
          .forwarder(new NettyHttpClient(new NettyHttpClientBuilder().build()))
          .processModel(process)
          .contentParser(_ -> valid((Void) null))
          .isDelivered(_ -> true)
          .isRejectedByInvalidResponse(_ -> false)
          .build();
      return new ProcessModelWithDefaultMissingResponse(process, exchange);
    }

  }

}
