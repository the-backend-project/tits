package com.github.thxmasj.statemachine.http.outbox;

import static com.github.thxmasj.statemachine.BuiltinEventTypes.Rollback;
import static com.github.thxmasj.statemachine.EntityModel.Begin;
import static com.github.thxmasj.statemachine.EntitySelector.entityId;
import static com.github.thxmasj.statemachine.EntitySelector.newEntityId;
import static com.github.thxmasj.statemachine.EventTrigger.trigger;
import static com.github.thxmasj.statemachine.TransitionModelBuilder.WithEvent.onEvent;
import static com.github.thxmasj.statemachine.TransitionModelBuilder.assemble;
import static com.github.thxmasj.statemachine.Tuples.tuple;
import static com.github.thxmasj.statemachine.Validated.invalid;
import static com.github.thxmasj.statemachine.Validated.valid;
import static com.github.thxmasj.statemachine.http.outbox.EventTypes.InvalidResponse;
import static com.github.thxmasj.statemachine.http.outbox.EventTypes.ResponseReceived;
import static com.github.thxmasj.statemachine.http.outbox.EventTypes.TimeoutExpired;
import static com.github.thxmasj.statemachine.message.http.HttpRequestMessage.Method.POST;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.thxmasj.statemachine.BasicEventType;
import com.github.thxmasj.statemachine.BasicEventType.Rollback.Data;
import com.github.thxmasj.statemachine.DataType;
import com.github.thxmasj.statemachine.DelaySpecification;
import com.github.thxmasj.statemachine.EntityId;
import com.github.thxmasj.statemachine.EntityModel;
import com.github.thxmasj.statemachine.EntitySelector;
import com.github.thxmasj.statemachine.Event;
import com.github.thxmasj.statemachine.EventLog;
import com.github.thxmasj.statemachine.EventReference;
import com.github.thxmasj.statemachine.EventType;
import com.github.thxmasj.statemachine.Init;
import com.github.thxmasj.statemachine.State;
import com.github.thxmasj.statemachine.StateMachine;
import com.github.thxmasj.statemachine.TransitionModelBuilder;
import com.github.thxmasj.statemachine.TransitionModelBuilder.ChangeContext;
import com.github.thxmasj.statemachine.TransitionModelBuilder.ChangeContext.InitialChangeContext;
import com.github.thxmasj.statemachine.TransitionModelBuilder.ChangeContext.TriggerChangeContext;
import com.github.thxmasj.statemachine.TransitionModelBuilder.TransitionModel;
import com.github.thxmasj.statemachine.Tuples.Tuple2;
import com.github.thxmasj.statemachine.http.NettyHttpClient;
import com.github.thxmasj.statemachine.http.NettyHttpClientBuilder;
import com.github.thxmasj.statemachine.message.http.HttpResponseMessage;
import com.github.thxmasj.statemachine.message.http.TypedHttpRequest;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

public class HttpOutboxTest {

  enum States implements State {
    WaitingForResponse {@Override public Timeout<?> timeout() {return rollbackAfter(Duration.ofSeconds(2));}},
    Done
  }
  static HttpServer server;

  @BeforeAll
  public static void setup() throws IOException {
    server = Init.httpServer();
  }

  private static <T> TypedHttpRequest<T> requestMessage(String path) {
    return new TypedHttpRequest<>(
        POST,
        URI.create("http://localhost:" + server.getAddress().getPort() + path),
        Map.of(),
        null
    );
  }

  private static <T> TypedHttpRequest<T> requestMessage(String path, T payload) {
    return TypedHttpRequest.create(
        POST,
        URI.create("http://localhost:" + server.getAddress().getPort() + path),
        payload
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
  public void requestDispatchedOutputDataType() {
    var exchange = HttpOutboxRequest.atMostOnce()
        .name("ExchangeWithTypedPayload")
        .id(UUID.randomUUID())
        .requestPayloadType(DataType.string())
        .responsePayloadType(DataType.string())
        .<Void>messageCreator(_ -> requestMessage("/test", "payload"))
        .forwarder(new NettyHttpClient(new NettyHttpClientBuilder().build()))
        .contentParser(_ -> valid("response"))
        .isAccepted(_ -> true)
        .isRejected(_ -> false)
        .isRejectedByInvalidResponse(_ -> false)
        .build();

    assertNotNull(exchange.requestDispatched().outputDataType());
    assertEquals("(HTTP request<string>, uuid)", exchange.requestDispatched().outputDataType().name());
  }

  @Test
  public void atLeastOnceRequestDispatchedOutputDataType() {
    var exchange = HttpOutboxRequest.atLeastOnce()
        .name("AtLeastOnceWithTypedPayload")
        .id(UUID.randomUUID())
        .requestPayloadType(DataType.string())
        .responsePayloadType(DataType.string())
        .<Void>messageCreator(_ -> requestMessage("/test", "payload"))
        .forwarder(new NettyHttpClient(new NettyHttpClientBuilder().build()))
        .contentParser(_ -> valid("response"))
        .isAccepted(_ -> true)
        .isFailureTransient(_ -> true)
        .isRejectedByInvalidResponse(_ -> false)
        .isFailureByInvalidResponseTransient(_ -> false)
        .isAttemptAvailable(_ -> true)
        .backoffAlgorithm(_ -> Duration.ofSeconds(1))
        .build();

    assertNotNull(exchange.requestDispatched().outputDataType());
    assertEquals("(HTTP request<string>, uuid)", exchange.requestDispatched().outputDataType().name());
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
          assertArrayEquals("Invalid response".getBytes(), event.data());
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
          assertArrayEquals("Invalid response".getBytes(), event.data());
        })
        .thenCancel().verify();
  }

  @Test
  public void missingResponseTransitionIdentifiesProcessEntity() {
    var model = ProcessModel.create();
    var inFlightTransitions = model.exchange().transitions().get(AtMostOnce.States.InFlight);
    @SuppressWarnings("unchecked")
    TransitionModel<Void, Void> timeoutTransition = (TransitionModel<Void, Void>) inFlightTransitions.stream()
        .filter(t -> t.eventType().equals(TimeoutExpired))
        .findFirst()
        .orElseThrow();
    assertEquals(1, timeoutTransition.triggers().size());
    var trigger = timeoutTransition.triggers().getFirst();
    assertEquals(model.missingResponse(), trigger.eventSpec().eventType());
    assertEquals(model.process(), trigger.entityModel());

    UUID processEntityId = UUID.randomUUID();
    @SuppressWarnings("unchecked")
    EventType<Void, Tuple2<TypedHttpRequest<Void>, UUID>> reqDispatchedType = (EventType<Void, Tuple2<TypedHttpRequest<Void>, UUID>>) (Object) model.exchange().requestDispatched();
    var reqDispatchedEvent = new Event<>(
        UUID.randomUUID(),
        1,
        reqDispatchedType,
        Clock.systemUTC(),
        tuple(new TypedHttpRequest<>(POST, URI.create("http://localhost/test"), Map.of(), null), processEntityId)
    );
    var log = new EventLog(model.exchange(), new EntityId.UUID(UUID.randomUUID()), List.of(), List.of(reqDispatchedEvent));
    var initialContext = new InitialChangeContext<Void>(null, null, null, timeoutTransition, AtMostOnce.States.InFlight, 2, log, ZonedDateTime.now(), "corr-1", null, model.machine(), List.of());
    var occ = timeoutTransition.calculate(initialContext).block();
    assertNotNull(occ);

    var triggerContext = (TriggerChangeContext<?, ?>) occ.previous();
    assertNotNull(triggerContext);
    assertEquals(model.missingResponse(), triggerContext.eventTrigger().eventSpec().eventType());
    assertEquals(model.process(), triggerContext.eventTrigger().entityModel());
  }

  @Test
  public void beginRequestDispatchedOutputsTriggerEntityId() {
    var model = ProcessModel.create();
    var beginTransitions = model.exchange().transitions().get(Begin);
    @SuppressWarnings("unchecked")
    TransitionModel<Void, Tuple2<TypedHttpRequest<Void>, UUID>> reqDispatchedTransition = (TransitionModel<Void, Tuple2<TypedHttpRequest<Void>, UUID>>) beginTransitions.stream()
        .filter(t -> t.eventType().equals(model.exchange().requestDispatched()))
        .findFirst()
        .orElseThrow();

    UUID triggerEntityId = UUID.randomUUID();
    var triggerEvent = new EventReference(triggerEntityId, 1);
    var log = new EventLog(model.exchange(), new EntityId.UUID(UUID.randomUUID()), List.of(), List.of());
    var initialContext = new InitialChangeContext<Void>(null, null, triggerEvent, reqDispatchedTransition, Begin, 1, log, ZonedDateTime.now(), "corr-1", null, model.machine(), List.of());
    var occ = reqDispatchedTransition.calculate(initialContext).block();
    assertNotNull(occ);
    assertTrue(occ.stepOutput().isAccepted());
    var acceptedEvent = occ.stepOutput().accepted().event();
    assertEquals(model.exchange().requestDispatched(), acceptedEvent.type());
    @SuppressWarnings("unchecked")
    var outputData = (Tuple2<TypedHttpRequest<Void>, UUID>) acceptedEvent.getUnmarshalledData();
    assertNotNull(outputData);
    assertNotNull(outputData.t1());
    assertEquals(triggerEntityId, outputData.t2());
  }

  @Test
  public void atLeastOnceSuccessTransitionIdentifiesProcessEntity() {
    EntityModel process = EntityModel.of("Process", UUID.fromString("dda0cc10-3356-4522-8527-ca4f7006c566"));
    EventType<Void, Void> processed = BasicEventType.of("Processed", UUID.fromString("433d18b2-8429-4615-aeb0-5de2240413ea"));
    AtLeastOnce<Void> alo = HttpOutboxRequest.atLeastOnce()
        .name("ALOExchange")
        .id(UUID.randomUUID())
        .requestPayloadType(DataType.none())
        .responsePayloadType(DataType.none())
        .<Void>messageCreator(_ -> requestMessage("/test"))
        .repeatMessageCreator((_, o) -> o)
        .forwarder(new NettyHttpClient(new NettyHttpClientBuilder().build()))
        .processModel(process)
        .contentParser(_ -> valid(null))
        .onSuccess(processed)
        .isAccepted(r -> r.statusCode() == 200)
        .isFailureTransient(_ -> false)
        .isRejectedByInvalidResponse(_ -> false)
        .isFailureByInvalidResponseTransient(_ -> false)
        .isAttemptAvailable(_ -> false)
        .backoffAlgorithm(_ -> Duration.ofSeconds(1))
        .build();

    var machine = Init.stateMachine(process, Map.of(Begin, List.of(onEvent(processed).to(States.Done).output())), List.of(), List.of(alo));

    var inFlightTransitions = alo.transitions().get(AtLeastOnce.States.InFlight);
    @SuppressWarnings("unchecked")
    TransitionModel<HttpResponseMessage, ?> responseReceivedTransition = (TransitionModel<HttpResponseMessage, ?>) inFlightTransitions.stream()
        .filter(t -> t.eventType().equals(ResponseReceived))
        .findFirst()
        .orElseThrow();

    UUID processEntityId = UUID.randomUUID();
    @SuppressWarnings("unchecked")
    EventType<Void, Tuple2<TypedHttpRequest<Void>, UUID>> reqDispatchedType = (EventType<Void, Tuple2<TypedHttpRequest<Void>, UUID>>) (Object) alo.requestDispatched();
    var reqDispatchedEvent = new Event<>(
        UUID.randomUUID(),
        1,
        reqDispatchedType,
        Clock.systemUTC(),
        tuple(new TypedHttpRequest<>(POST, URI.create("http://localhost/test"), Map.of(), null), processEntityId)
    );
    var log = new EventLog(alo, new EntityId.UUID(UUID.randomUUID()), List.of(), List.of(reqDispatchedEvent));
    var responseMessage = new HttpResponseMessage(200, "OK", Map.of(), null);
    var initialContext = new InitialChangeContext<HttpResponseMessage>(null, null, null, responseReceivedTransition, AtLeastOnce.States.InFlight, 2, log, ZonedDateTime.now(), "corr-1", responseMessage, machine, List.of());
    var occ = responseReceivedTransition.calculate(initialContext).block();
    assertNotNull(occ);

    TriggerChangeContext<?, ?> triggerContext = null;
    for (ChangeContext<?> c = occ; c != null; c = c.previous()) {
      if (c instanceof TriggerChangeContext<?, ?> tc) {
        triggerContext = tc;
        break;
      }
    }
    assertNotNull(triggerContext);
    assertEquals(processed, triggerContext.eventTrigger().eventSpec().eventType());
    assertEquals(process, triggerContext.eventTrigger().entityModel());
  }

  @Test
  public void atLeastOnceBeginRequestDispatchedOutputsTriggerEntityId() {
    EntityModel process = EntityModel.of("Process", UUID.fromString("dda0cc10-3356-4522-8527-ca4f7006c566"));
    AtLeastOnce<Void> alo = HttpOutboxRequest.atLeastOnce()
        .name("ALOExchange")
        .id(UUID.randomUUID())
        .requestPayloadType(DataType.none())
        .responsePayloadType(DataType.none())
        .<Void>messageCreator(_ -> requestMessage("/test"))
        .repeatMessageCreator((_, o) -> o)
        .forwarder(new NettyHttpClient(new NettyHttpClientBuilder().build()))
        .processModel(process)
        .contentParser(_ -> valid(null))
        .isAccepted(r -> r.statusCode() == 200)
        .isFailureTransient(_ -> false)
        .isRejectedByInvalidResponse(_ -> false)
        .isFailureByInvalidResponseTransient(_ -> false)
        .isAttemptAvailable(_ -> false)
        .backoffAlgorithm(_ -> Duration.ofSeconds(1))
        .build();

    var machine = Init.stateMachine(process, Map.of(Begin, List.of()), List.of(), List.of(alo));

    var beginTransitions = alo.transitions().get(Begin);
    @SuppressWarnings("unchecked")
    TransitionModel<Void, Tuple2<TypedHttpRequest<Void>, UUID>> reqDispatchedTransition = (TransitionModel<Void, Tuple2<TypedHttpRequest<Void>, UUID>>) beginTransitions.stream()
        .filter(t -> t.eventType().equals(alo.requestDispatched()))
        .findFirst()
        .orElseThrow();

    UUID triggerEntityId = UUID.randomUUID();
    var triggerEvent = new EventReference(triggerEntityId, 1);
    var log = new EventLog(alo, new EntityId.UUID(UUID.randomUUID()), List.of(), List.of());
    var initialContext = new InitialChangeContext<Void>(null, null, triggerEvent, reqDispatchedTransition, Begin, 1, log, ZonedDateTime.now(), "corr-1", null, machine, List.of());
    var occ = reqDispatchedTransition.calculate(initialContext).block();
    assertNotNull(occ);
    assertTrue(occ.stepOutput().isAccepted());
    var acceptedEvent = occ.stepOutput().accepted().event();
    assertEquals(alo.requestDispatched(), acceptedEvent.type());
    @SuppressWarnings("unchecked")
    var outputData = (Tuple2<TypedHttpRequest<Void>, UUID>) acceptedEvent.getUnmarshalledData();
    assertNotNull(outputData);
    assertNotNull(outputData.t1());
    assertEquals(triggerEntityId, outputData.t2());
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
    assertEquals(TimeoutExpired, timeoutTransition.triggers().getFirst().eventSpec().eventType());
  }

  record ProcessModel(
      EntityModel process,
      EventType<Void, UUID> doProcess,
      EventType<Void, Void> processed,
      EventType<Void, Void> failed,
      EventType<Void, Void> missingResponse,
      EventType<Void, Void> unknown,
      AtMostOnce<Void, Void> exchange,
      StateMachine machine,
      String path
  ) {
    static ProcessModel create() {
      return create(Duration.ofSeconds(10));
    }

    static ProcessModel create(Duration inflightTimeout) {
      EntityModel process = EntityModel.of("Process", UUID.fromString("dda0cc10-3356-4522-8527-ca4f7006c566"));
      EventType<Void, UUID> doProcess = BasicEventType.of(
          "Do process", UUID.fromString("dbcf351c-b50e-4789-80e9-f52e2be789cd"),
          DataType.none(), DataType.uuid()
      );
      EventType<Void, Void> processed = BasicEventType.of("Processed", UUID.fromString("433d18b2-8429-4615-aeb0-5de2240413ea"));
      EventType<Void, Void> failed = BasicEventType.of("Failed", UUID.fromString("0f4b6aea-a9c2-4b33-b315-844a4e32e45b"));
      EventType<Void, Void> missingResponse = BasicEventType.of("Missing response", UUID.fromString("7cd9c1f1-1ce0-4249-a709-5e11ea2f6404"));
      EventType<Void, Void> unknown = BasicEventType.of("Unknown", UUID.fromString("46cf31dd-56ed-41cb-b2ca-501930a23e44"));
      String path = "/" + UUID.randomUUID();
      AtMostOnce<Void, Void> Exchange = HttpOutboxRequest.atMostOnce()
          .name("Exchange")
          .id(UUID.fromString("a3778c63-144f-4748-9e8b-cc10ee20db3f"))
          .requestPayloadType(DataType.none())
          .responsePayloadType(DataType.none())
          .<Void>messageCreator(_ -> requestMessage(path))
          .forwarder(new NettyHttpClient(new NettyHttpClientBuilder().build()))
          .inflightTimeout(inflightTimeout)
          .processModel(process)
          .onPeerUnavailable(failed)
          .onMissingResponse(missingResponse)
          .onInvalidResponseRejection(failed)
          .onInvalidResponseUnknown(unknown)
          .contentParser(message -> message.statusCode() == 200 && message.body() == null ? valid((Void) null) : invalid("Invalid response"))
          .onSuccess(processed)
          .onFailure(failed)
          .isAccepted(r -> r.statusCode() >= 200 && r.statusCode() <= 299)
          .isRejected(r -> r.statusCode() >= 400 && r.statusCode() <= 499)
          .isRejectedByInvalidResponse(r -> r.t1().statusCode() >= 400 && r.t1().statusCode() <= 499)
          .rollbackModel(HttpOutboxRequest.atLeastOnce()
              .name("ExchangeRollback")
              .id(UUID.fromString("fd4959b4-5c14-4b7a-8ea5-b559b94f803c"))
              .requestPayloadType(DataType.none())
              .responsePayloadType(DataType.none())
              .<Void>messageCreator(_ -> requestMessage("/rollback"))
              .repeatMessageCreator((_, o) -> o)
              .forwarder(new NettyHttpClient(new NettyHttpClientBuilder().build()))
              .contentParser(_ -> valid(null))
              .isAccepted(r -> r.statusCode() >= 200 && r.statusCode() <= 299)
              .isFailureTransient(r -> r.statusCode() >= 500 && r.statusCode() <= 599)
              .isRejectedByInvalidResponse(r -> r.t1().statusCode() >= 400 && r.t1().statusCode() <= 499)
              .isFailureByInvalidResponseTransient(r -> r.t1().statusCode() >= 400 && r.t1().statusCode() <= 499)
              .isAttemptAvailable(c -> Duration.between(c.enqueueTime(), c.now()).compareTo(Duration.ofHours(5)) < 0)
              .backoffAlgorithm(c -> new DelaySpecification(Duration.ofSeconds(10), Duration.ofMinutes(10), Duration.ofHours(5), 1.5).calculateDelay(c.attemptNumber()))
              .build()
          )
          .build();

      Map<State, List<TransitionModel<?, ?>>> processTransitions = Map.of(
          Begin, List.of(
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
              onEvent(missingResponse).to(States.Done).output(),
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
      return new ProcessModel(process, doProcess, processed, failed, missingResponse, unknown, Exchange, machine, path);
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
      EntityModel process = EntityModel.of("Process", UUID.randomUUID());
      EventType<Void, Void> doProcess = BasicEventType.of("Do process", UUID.randomUUID());
      EventType<Void, Void> processed = BasicEventType.of("Processed", UUID.randomUUID());
      EventType<Void, Void> failed = BasicEventType.of("Failed", UUID.randomUUID());
      String path = "/" + UUID.randomUUID();
      HttpOutboxRequest<Void> Exchange = HttpOutboxRequest.atMostOnce()
          .name("ExchangeWithoutRollback")
          .id(UUID.fromString("a3778c63-144f-4748-9e8b-cc10ee20db3f"))
          .requestPayloadType(DataType.none())
          .responsePayloadType(DataType.none())
          .<Void>messageCreator(_ -> requestMessage(path))
          .forwarder(new NettyHttpClient(new NettyHttpClientBuilder().build()))
          .processModel(process)
          .onPeerUnavailable(failed)
          .onMissingResponse(failed)
          .onInvalidResponseRejection(failed)
          .onInvalidResponseUnknown(failed)
          .contentParser(_ -> valid(null))
          .onSuccess(processed)
          .onFailure(failed)
          .isAccepted(r -> r.statusCode() >= 200 && r.statusCode() <= 299)
          .isRejected(r -> r.statusCode() >= 400 && r.statusCode() <= 499)
          .isRejectedByInvalidResponse(r -> r.t1().statusCode() >= 400 && r.t1().statusCode() <= 499)
          .build();

      Map<State, List<TransitionModel<?, ?>>> processTransitions = Map.of(
          Begin, List.of(
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
      EntityModel process = EntityModel.of("Process", UUID.randomUUID());
      EventType<Void, Void> doProcess = BasicEventType.of("Do process", UUID.randomUUID());
      String path = "/" + UUID.randomUUID();
      HttpOutboxRequest<Void> Exchange = HttpOutboxRequest.atMostOnce()
          .name("ExchangeWithDefaultInvalidResponse")
          .id(UUID.fromString("a3778c63-144f-4748-9e8b-cc10ee20db3f"))
          .requestPayloadType(DataType.none())
          .responsePayloadType(DataType.none())
          .<Void>messageCreator(_ -> requestMessage(path))
          .forwarder(new NettyHttpClient(new NettyHttpClientBuilder().build()))
          .processModel(process)
          .contentParser(_ -> invalid("Invalid response"))
          .isAccepted(_ -> false)
          .isRejected(_ -> false)
          .isRejectedByInvalidResponse(_ -> true)
          .build();

      Map<State, List<TransitionModel<?, ?>>> processTransitions = Map.of(
          Begin, List.of(
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
      EntityModel process = EntityModel.of("Process", UUID.randomUUID());
      EventType<Void, Void> doProcess = BasicEventType.of("Do process", UUID.randomUUID());
      String path = "/" + UUID.randomUUID();
      HttpOutboxRequest<Void> Exchange = HttpOutboxRequest.atMostOnce()
          .name("ExchangeWithDefaultInvalidResponseUnknown")
          .id(UUID.fromString("a3778c63-144f-4748-9e8b-cc10ee20db3f"))
          .requestPayloadType(DataType.unknown())
          .responsePayloadType(DataType.unknown())
          .<Void>messageCreator(_ -> requestMessage(path))
          .forwarder(new NettyHttpClient(new NettyHttpClientBuilder().build()))
          .processModel(process)
          .contentParser(_ -> invalid("Invalid response"))
          .isAccepted(_ -> false)
          .isRejected(_ -> false)
          .isRejectedByInvalidResponse(_ -> false)
          .build();

      Map<State, List<TransitionModel<?, ?>>> processTransitions = Map.of(
          Begin, List.of(
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
      EntityModel process = EntityModel.of("Process", UUID.randomUUID());
      String path = "/" + UUID.randomUUID();
      HttpOutboxRequest<Void> exchange = HttpOutboxRequest.atMostOnce()
          .name("ExchangeWithDefaultMissingResponse")
          .id(UUID.fromString("a3778c63-144f-4748-9e8b-cc10ee20db3f"))
          .requestPayloadType(DataType.unknown())
          .responsePayloadType(DataType.unknown())
          .<Void>messageCreator(_ -> requestMessage(path))
          .forwarder(new NettyHttpClient(new NettyHttpClientBuilder().build()))
          .processModel(process)
          .contentParser(_ -> valid((Void) null))
          .isAccepted(_ -> true)
          .isRejected(_ -> false)
          .isRejectedByInvalidResponse(_ -> false)
          .build();
      return new ProcessModelWithDefaultMissingResponse(process, exchange);
    }

  }

}
