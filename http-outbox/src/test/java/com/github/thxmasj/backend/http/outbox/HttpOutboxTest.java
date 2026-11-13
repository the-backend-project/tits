package com.github.thxmasj.backend.http.outbox;

import static com.github.thxmasj.backend.core.BuiltinEventTypes.EventsRolledBack;
import static com.github.thxmasj.backend.core.EntityModel.Begin;
import static com.github.thxmasj.backend.core.EntitySelector.entityId;
import static com.github.thxmasj.backend.core.EntitySelector.newEntityId;
import static com.github.thxmasj.backend.core.EventTrigger.trigger;
import static com.github.thxmasj.backend.core.TransitionModelBuilder.WithEvent.onEvent;
import static com.github.thxmasj.backend.core.TransitionModelBuilder.assemble;
import static com.github.thxmasj.backend.core.Tuples.tuple;
import static com.github.thxmasj.backend.core.Validated.invalid;
import static com.github.thxmasj.backend.core.Validated.valid;
import static com.github.thxmasj.backend.http.outbox.EventTypes.InvalidResponseReceived;
import static com.github.thxmasj.backend.http.outbox.EventTypes.ResponseReceived;
import static com.github.thxmasj.backend.http.outbox.EventTypes.TimeoutExpired;
import static com.github.thxmasj.backend.http.outbox.HttpOutboxRequest.Index;
import static com.github.thxmasj.backend.core.message.http.HttpRequestMessage.Method.POST;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.thxmasj.backend.core.BasicEventType;
import com.github.thxmasj.backend.core.BasicEventType.Rollback.Data;
import com.github.thxmasj.backend.core.Calculation;
import com.github.thxmasj.backend.core.DataType;
import com.github.thxmasj.backend.core.DelaySpecification;
import com.github.thxmasj.backend.core.EntityModel;
import com.github.thxmasj.backend.core.Event;
import com.github.thxmasj.backend.core.EventLog;
import com.github.thxmasj.backend.core.EventReference;
import com.github.thxmasj.backend.core.EventType;
import com.github.thxmasj.backend.core.test.Init;
import com.github.thxmasj.backend.core.State;
import com.github.thxmasj.backend.core.StateMachine;
import com.github.thxmasj.backend.core.TransitionStep;
import com.github.thxmasj.backend.core.TransitionStep.DelayedTriggerStep;
import com.github.thxmasj.backend.core.TransitionStep.InputStep;
import com.github.thxmasj.backend.core.TransitionStep.TriggerStep;
import com.github.thxmasj.backend.core.TransitionModel;
import com.github.thxmasj.backend.core.Tuples.Tuple2;
import com.github.thxmasj.backend.core.http.NettyHttpClient;
import com.github.thxmasj.backend.core.http.NettyHttpClientBuilder;
import com.github.thxmasj.backend.http.outbox.AtMostOnceWithRollbackDispatcher;
import com.github.thxmasj.backend.http.outbox.HttpOutboxRequest.BatchedRequest;
import com.github.thxmasj.backend.core.message.http.HttpResponseMessage;
import com.github.thxmasj.backend.core.message.http.TypedHttpRequest;
import com.github.thxmasj.backend.core.message.http.TypedHttpResponse;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

public class HttpOutboxTest {

  enum States implements State {
    WaitingForResponse,
    Done
  }
  static HttpServer server;

  @BeforeAll
  public static void setup() throws IOException {
    server = Init.httpServer();
  }

  private static StateMachine stateMachine(
      EntityModel entityModel,
      Map<State, List<TransitionModel<?, ?>>> processTransitions,
      List<HttpOutboxRequest<?>> outboxes
  ) {
    Map<EntityModel, Map<State, List<TransitionModel<?, ?>>>> outboxTransitions = new HashMap<>();
    for (var outbox : outboxes) {
      outboxTransitions.put(outbox, outbox.transitions());
    }
    return stateMachine(entityModel, processTransitions, outboxTransitions);
  }

  private static StateMachine stateMachine(
      EntityModel entityModel,
      Map<State, List<TransitionModel<?, ?>>> processTransitions,
      Map<EntityModel, Map<State, List<TransitionModel<?, ?>>>> outboxTransitions
  ) {
    Map<EntityModel, Map<State, List<TransitionModel<?, ?>>>> transitions = new HashMap<>(outboxTransitions);
    transitions.put(entityModel, processTransitions);
    transitions.put(Index, Index.transitions());
    return Init.stateMachine(transitions);
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
    StepVerifier.create(model.machine().onEvent(trigger(model.processStarted(), model.process())))
        .assertNext(event -> assertEquals(model.processStarted(), event.type()))
        .assertNext(event -> assertEquals(model.processSucceeded(), event.type()))
        .thenCancel().verify();
  }

  @Test
  public void failedExchange() {
    var model = ProcessModel.create();
    Init.addBadRequestContext(server, model.path());
    StepVerifier.create(model.machine().onEvent(trigger(model.processStarted(), model.process())))
        .assertNext(event -> assertEquals(model.processStarted(), event.type()))
        .assertNext(event -> assertEquals(model.processFailed(), event.type()))
        .thenCancel().verify();
  }

  @Test
  public void unknownExchange() {
    unknownExchange(ProcessModel.create());
  }

  private Event<?> unknownExchange(ProcessModel model) {
    Init.addInternalServerErrorContext(server, model.path());
    var events = model.machine().onEvent(trigger(model.processStarted(), model.process()))
        .take(2)
        .collectList()
        .block();
    assertNotNull(events);
    assertEquals(model.processStarted(), events.get(0).type());
    assertEquals(model.outcomeUndetermined(), events.get(1).type());
    return events.get(1);
  }

  @Test
  public void rollbackOfUnknownExchange() {
    var model = ProcessModel.create();
    Init.addOkContext(server, model.path() + "/rollback");
    var unknown = unknownExchange(model);
    var rollback = model.machine().onEvent(
        trigger(EventsRolledBack, model.process(), unknown.entityId()),
        new Data(0, "Technical")
    ).blockFirst();
    assertNotNull(rollback);
    assertEquals(EventsRolledBack, rollback.type());
  }

  @Test
  public void rollbackOfAcceptedExchange() {
    var model = ProcessModel.create();
    Init.addOkContext(server, model.path());
    Init.addOkContext(server, model.path() + "/rollback");
    var events = model.machine().onEvent(trigger(model.processStarted(), model.process()))
        .take(2)
        .collectList()
        .block();
    assertNotNull(events);
    assertEquals(model.processStarted(), events.get(0).type());
    assertEquals(model.processSucceeded(), events.get(1).type());
    var rollback = model.machine().onEvent(
        trigger(EventsRolledBack, model.process(), events.get(1).entityId()),
        new Data(0, "Technical")
    ).blockFirst();
    assertNotNull(rollback);
    assertEquals(EventsRolledBack, rollback.type());
  }

  @Test
  public void rollbackModelsUsedFromAcceptedAndUnknownStates() {
    var model = ProcessModel.create();
    var acceptedTransitions = model.exchange().transitions().get(AtMostOnce.States.Accepted);
    assertEquals(1, acceptedTransitions.size());
    assertEquals(model.exchange().rollbackOnAcceptedModel(), acceptedTransitions.getFirst().triggers().getFirst().entityModel());

    var unknownTransitions = model.exchange().transitions().get(AtMostOnce.States.Unknown);
    assertEquals(1, unknownTransitions.size());
    assertEquals(model.exchange().rollbackOnUnknownModel(), unknownTransitions.getFirst().triggers().getFirst().entityModel());
  }

  @Test
  public void requestDispatchedOutputDataType() {
    var exchange = HttpOutboxRequest.atMostOnce()
        .requestPayloadType(DataType.string())
        .responsePayloadType(DataType.string())
        .forwarder(new NettyHttpClient(new NettyHttpClientBuilder().build()))
        .contentParser(_ -> valid("response"))
        .isAccepted(_ -> true)
        .isRejected(_ -> false)
        .isRejectedByInvalidResponse(_ -> false)
        .build("ExchangeWithTypedPayload", UUID.randomUUID());

    assertNotNull(exchange.requestDispatched().outputDataType());
    assertEquals("(HTTP request<string>, uuid)", exchange.requestDispatched().outputDataType().name());
  }

  @Test
  public void atLeastOnceRequestDispatchedOutputDataType() {
    var exchange = HttpOutboxRequest.atLeastOnce()
        .requestPayloadType(DataType.string())
        .responsePayloadType(DataType.string())
        .forwarder(new NettyHttpClient(new NettyHttpClientBuilder().build()))
        .contentParser(_ -> valid("response"))
        .isAccepted(_ -> true)
        .isFailureTransient(_ -> true)
        .isRejectedByInvalidResponse(_ -> false)
        .isFailureByInvalidResponseTransient(_ -> false)
        .isAttemptAvailable(_ -> true)
        .backoffAlgorithm(_ -> Duration.ofSeconds(1))
        .build("AtLeastOnceWithTypedPayload", UUID.randomUUID());

    assertNotNull(exchange.requestDispatched().outputDataType());
    assertEquals("(HTTP request<string>, uuid)", exchange.requestDispatched().outputDataType().name());
  }

  @Test
  public void batchedRequestDataTypeRoundTrip() {
    var dataType = BatchedRequest.dataType(DataType.string());
    var request = new BatchedRequest<>(TypedHttpRequest.create(POST, URI.create("http://localhost/test"), "payload"), UUID.randomUUID());
    assertEquals(request, dataType.unmarshal(dataType.marshal(request)));
  }

  @Test
  public void dispatcherCreatesInputOfBatchedOutbox() {
    var outbox = HttpOutboxRequest.atMostOnce()
        .requestPayloadType(DataType.string())
        .responsePayloadType(DataType.string())
        .forwarder(new NettyHttpClient(new NettyHttpClientBuilder().build()))
        .contentParser(_ -> valid("response"))
        .isAccepted(_ -> true)
        .isRejected(_ -> false)
        .isRejectedByInvalidResponse(_ -> false)
        .build("BatchedExchange", UUID.randomUUID());
    UUID batchId = UUID.randomUUID();
    BatchedAtMostOnceDispatcher<String, String> dispatcher = BatchedAtMostOnceDispatcher.of(
        outbox,
        (String path) -> new BatchedRequest<>(requestMessage(path, "payload"), batchId)
    );

    assertEquals(outbox, dispatcher.outbox());
    assertEquals(outbox.batchedRequestDispatched(), dispatcher.requestDispatched());
    assertEquals(new BatchedRequest<>(requestMessage("/test", "payload"), batchId), dispatcher.input(null, "/test").block());
    assertEquals("(HTTP request<string>, uuid)", outbox.batchedRequestDispatched().inputDataType().name());
  }

  @Test
  public void atMostOnceWithRequestAwareContentParser() {
    var exchange = HttpOutboxRequest.atMostOnce()
        .requestPayloadType(DataType.string())
        .responsePayloadType(DataType.string())
        .forwarder(new NettyHttpClient(new NettyHttpClientBuilder().build()))
        .contentParser((req, res) -> valid(req.payload() + " -> " + res.statusCode()))
        .isAccepted(_ -> true)
        .isRejected(_ -> false)
        .isRejectedByInvalidResponse(_ -> false)
        .build("ExchangeWithRequestAwareContentParser", UUID.randomUUID());

    assertNotNull(exchange.requestDispatched().outputDataType());
    assertEquals("(HTTP request<string>, uuid)", exchange.requestDispatched().outputDataType().name());
  }

  @Test
  public void atLeastOnceWithRequestAwareContentParser() {
    var exchange = HttpOutboxRequest.atLeastOnce()
        .requestPayloadType(DataType.string())
        .responsePayloadType(DataType.string())
        .forwarder(new NettyHttpClient(new NettyHttpClientBuilder().build()))
        .contentParser((req, res) -> valid(req.payload() + " -> " + res.statusCode()))
        .isAccepted(_ -> true)
        .isFailureTransient(_ -> true)
        .isRejectedByInvalidResponse(_ -> false)
        .isFailureByInvalidResponseTransient(_ -> false)
        .isAttemptAvailable(_ -> true)
        .backoffAlgorithm(_ -> Duration.ofSeconds(1))
        .build("AtLeastOnceWithRequestAwareContentParser", UUID.randomUUID());

    assertNotNull(exchange.requestDispatched().outputDataType());
    assertEquals("(HTTP request<string>, uuid)", exchange.requestDispatched().outputDataType().name());
  }

  @Test
  public void successfulExchangeWithRequestAwareContentParser() {
    EntityModel process = EntityModel.of("Process", UUID.randomUUID());
    EventType<Void, Void> processStarted = BasicEventType.of("Process started", UUID.randomUUID());
    EventType<String, String> processSucceeded = BasicEventType.of("Process succeeded", UUID.randomUUID(), DataType.string(), DataType.string());
    EventType<Void, Void> processFailed = BasicEventType.of("Process failed", UUID.randomUUID());
    String path = "/" + UUID.randomUUID();
    HttpOutboxRequest<String> exchange = HttpOutboxRequest.atMostOnce()
        .requestPayloadType(DataType.string())
        .responsePayloadType(DataType.string())
        .forwarder(new NettyHttpClient(new NettyHttpClientBuilder().build()))
        .processModel(process)
        .onPeerUnavailable(processFailed)
        .onMissingResponse(processFailed)
        .onInvalidResponseRejection(processFailed)
        .onInvalidResponseUnknown(processFailed)
        .contentParser((req, res) -> valid(req.payload() + ":" + res.statusCode()))
        .onSuccess(processSucceeded, TypedHttpResponse::payload)
        .onFailure(processFailed)
        .isAccepted(r -> r.statusCode() >= 200 && r.statusCode() <= 299)
        .isRejected(r -> r.statusCode() >= 400 && r.statusCode() <= 499)
        .isRejectedByInvalidResponse(r -> r.t1().statusCode() >= 400 && r.t1().statusCode() <= 499)
        .build("ExchangeWithRequestAwareContentParser", UUID.fromString("a3778c63-144f-4748-9e8b-cc10ee20db3f"));

    Map<State, List<TransitionModel<?, ?>>> processTransitions = Map.of(
        Begin, List.of(
            onEvent(processStarted).to(States.WaitingForResponse)
                .trigger(exchange.requestDispatched()).with(_ -> requestMessage(path, "hello")).on(exchange).identifiedBy(newEntityId())
                .output()
        ),
        States.WaitingForResponse, List.of(
            onEvent(processSucceeded).to(States.Done)
                .assembleInput()
                .output(d -> d),
            onEvent(processFailed).to(States.Done)
                .output()
        ),
        States.Done, List.of()
    );

    var machine = stateMachine(
        process,
        processTransitions,
        List.of(exchange)
    );

    Init.addOkContext(server, path);
    StepVerifier.create(machine.onEvent(trigger(processStarted, process)))
        .assertNext(event -> assertEquals(processStarted, event.type()))
        .assertNext(event -> {
          assertEquals(processSucceeded, event.type());
          assertArrayEquals("hello:200".getBytes(), event.data());
        })
        .thenCancel().verify();
  }

  @Test
  public void successfulExchangeWithoutRollback() {
    var model = ProcessModelWithoutRollback.create();
    Init.addOkContext(server, model.path());
    StepVerifier.create(model.machine().onEvent(trigger(model.processStarted(), model.process())))
        .assertNext(event -> assertEquals(model.processStarted(), event.type()))
        .assertNext(event -> assertEquals(model.processSucceeded(), event.type()))
        .thenCancel().verify();
  }

  @Test
  public void failedExchangeWithoutRollback() {
    var model = ProcessModelWithoutRollback.create();
    Init.addBadRequestContext(server, model.path());
    StepVerifier.create(model.machine().onEvent(trigger(model.processStarted(), model.process())))
        .assertNext(event -> assertEquals(model.processStarted(), event.type()))
        .assertNext(event -> assertEquals(model.processFailed(), event.type()))
        .thenCancel().verify();
  }

  @Test
  public void defaultInvalidResponseRejection() {
    var model = ProcessModelWithDefaultInvalidResponseRejection.create();
    Init.addBadRequestContext(server, model.path());
    StepVerifier.create(model.machine().onEvent(trigger(model.processStarted(), model.process())))
        .assertNext(event -> assertEquals(model.processStarted(), event.type()))
        .assertNext(event -> {
          assertEquals(InvalidResponseReceived, event.type());
          assertArrayEquals("Invalid response".getBytes(), event.data());
        })
        .thenCancel().verify();
  }

  @Test
  public void defaultInvalidResponseUnknown() {
    var model = ProcessModelWithDefaultInvalidResponseUnknown.create();
    Init.addBadRequestContext(server, model.path());
    StepVerifier.create(model.machine().onEvent(trigger(model.processStarted(), model.process())))
        .assertNext(event -> assertEquals(model.processStarted(), event.type()))
        .assertNext(event -> {
          assertEquals(InvalidResponseReceived, event.type());
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
    assertEquals(model.responseMissed(), trigger.eventSpec().eventType());
    assertEquals(model.process(), trigger.entityModel());

    UUID processEntityId = UUID.randomUUID();
    @SuppressWarnings("unchecked")
    EventType<TypedHttpRequest<Void>, Tuple2<TypedHttpRequest<Void>, UUID>> reqDispatchedType = (EventType<TypedHttpRequest<Void>, Tuple2<TypedHttpRequest<Void>, UUID>>) (Object) model.exchange().requestDispatched();
    var reqDispatchedEvent = new Event<>(
        UUID.randomUUID(),
        1,
        reqDispatchedType,
        Clock.systemUTC(),
        tuple(new TypedHttpRequest<>(POST, URI.create("http://localhost/test"), Map.of(), null), processEntityId)
    );
    var log = new EventLog(model.exchange(), UUID.randomUUID(), List.of(reqDispatchedEvent));
    var initialContext = new InputStep<Void>(null, null, null, timeoutTransition, AtMostOnce.States.InFlight, 2, log, ZonedDateTime.now(), "corr-1", null, model.machine(), new Calculation());
    var occ = timeoutTransition.calculate(initialContext).block();
    assertNotNull(occ);

    var triggerContext = (TriggerStep<?, ?>) occ.previous();
    assertNotNull(triggerContext);
    assertEquals(model.responseMissed(), triggerContext.eventTrigger().eventSpec().eventType());
    assertEquals(model.process(), triggerContext.eventTrigger().entityModel());
  }

  @Test
  public void beginRequestDispatchedOutputsTriggerEntityId() {
    var model = ProcessModel.create();
    var beginTransitions = model.exchange().transitions().get(Begin);
    @SuppressWarnings("unchecked")
    TransitionModel<TypedHttpRequest<Void>, Tuple2<TypedHttpRequest<Void>, UUID>> reqDispatchedTransition = (TransitionModel<TypedHttpRequest<Void>, Tuple2<TypedHttpRequest<Void>, UUID>>) beginTransitions.stream()
        .filter(t -> t.eventType().equals(model.exchange().requestDispatched()))
        .findFirst()
        .orElseThrow();

    UUID triggerEntityId = UUID.randomUUID();
    var triggerEvent = new EventReference(triggerEntityId, 1);
    var log = new EventLog(model.exchange(), UUID.randomUUID(), List.of());
    var initialContext = new InputStep<>(null, null, triggerEvent, reqDispatchedTransition, Begin, 1, log, ZonedDateTime.now(), "corr-1", HttpOutboxTest.<Void>requestMessage("/test"), model.machine(), new Calculation());
    var occ = reqDispatchedTransition.calculate(initialContext).block();
    assertNotNull(occ);
    assertTrue(occ.stepOutput().isAccepted());
    var acceptedEvent = occ.stepOutput().accepted().event();
    assertEquals(model.exchange().requestDispatched(), acceptedEvent.type());
    var outputData = acceptedEvent.getUnmarshalledData();
    assertNotNull(outputData);
    assertNotNull(outputData.t1());
    assertEquals(triggerEntityId, outputData.t2());
  }

  @Test
  public void atLeastOnceSuccessTransitionIdentifiesProcessEntity() {
    EntityModel process = EntityModel.of("Process", UUID.fromString("dda0cc10-3356-4522-8527-ca4f7006c566"));
    EventType<Void, Void> processSucceeded = BasicEventType.of("Process succeeded", UUID.fromString("433d18b2-8429-4615-aeb0-5de2240413ea"));
    AtLeastOnce<Void> alo = HttpOutboxRequest.atLeastOnce()
        .requestPayloadType(DataType.none())
        .responsePayloadType(DataType.none())
        .repeatMessageCreator((_, o) -> o)
        .forwarder(new NettyHttpClient(new NettyHttpClientBuilder().build()))
        .processModel(process)
        .contentParser(_ -> valid(null))
        .onSuccess(processSucceeded)
        .isAccepted(r -> r.statusCode() == 200)
        .isFailureTransient(_ -> false)
        .isRejectedByInvalidResponse(_ -> false)
        .isFailureByInvalidResponseTransient(_ -> false)
        .isAttemptAvailable(_ -> false)
        .backoffAlgorithm(_ -> Duration.ofSeconds(1))
        .build("ALOExchange", UUID.randomUUID());

    var machine = stateMachine(process, Map.of(Begin, List.of(onEvent(processSucceeded).to(States.Done).output())), List.of(alo));

    var inFlightTransitions = alo.transitions().get(AtLeastOnce.States.InFlight);
    @SuppressWarnings("unchecked")
    TransitionModel<HttpResponseMessage, ?> responseReceivedTransition = (TransitionModel<HttpResponseMessage, ?>) inFlightTransitions.stream()
        .filter(t -> t.eventType().equals(ResponseReceived))
        .findFirst()
        .orElseThrow();

    UUID processEntityId = UUID.randomUUID();
    @SuppressWarnings("unchecked")
    EventType<TypedHttpRequest<Void>, Tuple2<TypedHttpRequest<Void>, UUID>> reqDispatchedType = (EventType<TypedHttpRequest<Void>, Tuple2<TypedHttpRequest<Void>, UUID>>) (Object) alo.requestDispatched();
    var reqDispatchedEvent = new Event<>(
        UUID.randomUUID(),
        1,
        reqDispatchedType,
        Clock.systemUTC(),
        tuple(new TypedHttpRequest<>(POST, URI.create("http://localhost/test"), Map.of(), null), processEntityId)
    );
    var log = new EventLog(alo, UUID.randomUUID(), List.of(reqDispatchedEvent));
    var responseMessage = new HttpResponseMessage(200, "OK", Map.of(), null);
    var initialContext = new InputStep<HttpResponseMessage>(null, null, null, responseReceivedTransition, AtLeastOnce.States.InFlight, 2, log, ZonedDateTime.now(), "corr-1", responseMessage, machine, new Calculation());
    var occ = responseReceivedTransition.calculate(initialContext).block();
    assertNotNull(occ);

    TriggerStep<?, ?> triggerContext = null;
    for (TransitionStep<?> c = occ; c != null; c = c.previous()) {
      if (c instanceof TriggerStep<?, ?> tc) {
        triggerContext = tc;
        break;
      }
    }
    assertNotNull(triggerContext);
    assertEquals(processSucceeded, triggerContext.eventTrigger().eventSpec().eventType());
    assertEquals(process, triggerContext.eventTrigger().entityModel());
  }

  @Test
  public void atLeastOnceBeginRequestDispatchedOutputsTriggerEntityId() {
    EntityModel process = EntityModel.of("Process", UUID.fromString("dda0cc10-3356-4522-8527-ca4f7006c566"));
    AtLeastOnce<Void> alo = HttpOutboxRequest.atLeastOnce()
        .requestPayloadType(DataType.none())
        .responsePayloadType(DataType.none())
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
        .build("ALOExchange", UUID.randomUUID());

    var machine = stateMachine(process, Map.of(Begin, List.of()), List.of(alo));

    var beginTransitions = alo.transitions().get(Begin);
    @SuppressWarnings("unchecked")
    TransitionModel<TypedHttpRequest<Void>, Tuple2<TypedHttpRequest<Void>, UUID>> reqDispatchedTransition = (TransitionModel<TypedHttpRequest<Void>, Tuple2<TypedHttpRequest<Void>, UUID>>) beginTransitions.stream()
        .filter(t -> t.eventType().equals(alo.requestDispatched()))
        .findFirst()
        .orElseThrow();

    UUID triggerEntityId = UUID.randomUUID();
    var triggerEvent = new EventReference(triggerEntityId, 1);
    var log = new EventLog(alo, UUID.randomUUID(), List.of());
    var initialContext = new InputStep<>(null, null, triggerEvent, reqDispatchedTransition, Begin, 1, log, ZonedDateTime.now(), "corr-1", HttpOutboxTest.<Void>requestMessage("/test"), machine, new Calculation());
    var occ = reqDispatchedTransition.calculate(initialContext).block();
    assertNotNull(occ);
    assertTrue(occ.stepOutput().isAccepted());
    var acceptedEvent = occ.stepOutput().accepted().event();
    assertEquals(alo.requestDispatched(), acceptedEvent.type());
    var outputData = acceptedEvent.getUnmarshalledData();
    assertNotNull(outputData);
    assertNotNull(outputData.t1());
    assertEquals(triggerEntityId, outputData.t2());
  }

  @Test
  public void atLeastOnceRedispatchedRequestHasInflightTimeout() {
    EntityModel process = EntityModel.of("Process", UUID.fromString("dda0cc10-3356-4522-8527-ca4f7006c566"));
    Duration inflightTimeout = Duration.ofSeconds(7);
    AtLeastOnce<Void> alo = HttpOutboxRequest.atLeastOnce()
        .requestPayloadType(DataType.none())
        .responsePayloadType(DataType.none())
        .repeatMessageCreator((_, o) -> o)
        .forwarder(new NettyHttpClient(new NettyHttpClientBuilder().build()))
        .inflightTimeout(inflightTimeout)
        .processModel(process)
        .contentParser(_ -> valid(null))
        .isAccepted(r -> r.statusCode() == 200)
        .isFailureTransient(_ -> true)
        .isRejectedByInvalidResponse(_ -> false)
        .isFailureByInvalidResponseTransient(_ -> false)
        .isAttemptAvailable(_ -> true)
        .backoffAlgorithm(_ -> Duration.ofSeconds(1))
        .build("ALOExchange", UUID.randomUUID());

    var machine = stateMachine(process, Map.of(Begin, List.of()), List.of(alo));

    @SuppressWarnings("unchecked")
    TransitionModel<Void, TypedHttpRequest<Void>> redispatchedTransition = (TransitionModel<Void, TypedHttpRequest<Void>>) alo.transitions().get(AtLeastOnce.States.Compensating).getFirst();

    @SuppressWarnings("unchecked")
    EventType<TypedHttpRequest<Void>, Tuple2<TypedHttpRequest<Void>, UUID>> reqDispatchedType = (EventType<TypedHttpRequest<Void>, Tuple2<TypedHttpRequest<Void>, UUID>>) (Object) alo.requestDispatched();
    var reqDispatchedEvent = new Event<>(
        UUID.randomUUID(),
        1,
        reqDispatchedType,
        Clock.systemUTC(),
        tuple(new TypedHttpRequest<>(POST, URI.create("http://localhost/test"), Map.of(), null), UUID.randomUUID())
    );
    UUID requestId = UUID.randomUUID();
    var log = new EventLog(alo, requestId, List.of(reqDispatchedEvent));
    var now = ZonedDateTime.now();
    var initialContext = new InputStep<Void>(null, null, null, redispatchedTransition, AtLeastOnce.States.Compensating, 4, log, now, "corr-1", null, machine, new Calculation());
    var occ = redispatchedTransition.calculate(initialContext).block();
    assertNotNull(occ);

    DelayedTriggerStep<?, ?> delayedTriggerStep = null;
    for (TransitionStep<?> c = occ; c != null; c = c.previous()) {
      if (c instanceof DelayedTriggerStep<?, ?> dc) {
        delayedTriggerStep = dc;
        break;
      }
    }
    assertNotNull(delayedTriggerStep);
    var timeout = delayedTriggerStep.delayedEvent();
    assertEquals(TimeoutExpired, timeout.type());
    assertEquals(requestId, timeout.entityId());
    assertEquals(5, timeout.eventNumber());
    assertEquals(now.plus(inflightTimeout), timeout.after());
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
      EventType<Void, UUID> processStarted,
      EventType<Void, Void> processSucceeded,
      EventType<Void, Void> processFailed,
      EventType<Void, Void> responseMissed,
      EventType<Void, Void> outcomeUndetermined,
      AtMostOnce<Void, Void> exchange,
      StateMachine machine,
      String path
  ) {
    static ProcessModel create() {
      return create(Duration.ofSeconds(10));
    }

    static ProcessModel create(Duration inflightTimeout) {
      EntityModel process = EntityModel.of("Process", UUID.fromString("dda0cc10-3356-4522-8527-ca4f7006c566"));
      EventType<Void, UUID> processStarted = BasicEventType.of(
          "Process started", UUID.fromString("dbcf351c-b50e-4789-80e9-f52e2be789cd"),
          DataType.none(), DataType.uuid()
      );
      EventType<Void, Void> processSucceeded = BasicEventType.of("Process succeeded", UUID.fromString("433d18b2-8429-4615-aeb0-5de2240413ea"));
      EventType<Void, Void> processFailed = BasicEventType.of("Process failed", UUID.fromString("0f4b6aea-a9c2-4b33-b315-844a4e32e45b"));
      EventType<Void, Void> responseMissed = BasicEventType.of("Response missed", UUID.fromString("7cd9c1f1-1ce0-4249-a709-5e11ea2f6404"));
      EventType<Void, Void> outcomeUndetermined = BasicEventType.of("Outcome undetermined", UUID.fromString("46cf31dd-56ed-41cb-b2ca-501930a23e44"));
      String path = "/" + UUID.randomUUID();
      AtMostOnce<Void, Void> Exchange = HttpOutboxRequest.atMostOnce()
          .requestPayloadType(DataType.none())
          .responsePayloadType(DataType.none())
          .forwarder(new NettyHttpClient(new NettyHttpClientBuilder().build()))
          .inflightTimeout(inflightTimeout)
          .processModel(process)
          .onPeerUnavailable(processFailed)
          .onMissingResponse(responseMissed)
          .onInvalidResponseRejection(processFailed)
          .onInvalidResponseUnknown(outcomeUndetermined)
          .contentParser(message -> message.statusCode() == 200 && message.body() == null ? valid((Void) null) : invalid("Invalid response"))
          .onSuccess(processSucceeded)
          .onFailure(processFailed)
          .isAccepted(r -> r.statusCode() >= 200 && r.statusCode() <= 299)
          .isRejected(r -> r.statusCode() >= 400 && r.statusCode() <= 499)
          .isRejectedByInvalidResponse(r -> r.t1().statusCode() >= 400 && r.t1().statusCode() <= 499)
          .rollbackOnAcceptedModel(HttpOutboxRequest.atLeastOnce()
              .requestPayloadType(DataType.none())
              .responsePayloadType(DataType.none())
              .repeatMessageCreator((_, o) -> o)
              .forwarder(new NettyHttpClient(new NettyHttpClientBuilder().build()))
              .contentParser(_ -> valid(null))
              .isAccepted(r -> r.statusCode() >= 200 && r.statusCode() <= 299)
              .isFailureTransient(r -> r.statusCode() >= 500 && r.statusCode() <= 599)
              .isRejectedByInvalidResponse(r -> r.t1().statusCode() >= 400 && r.t1().statusCode() <= 499)
              .isFailureByInvalidResponseTransient(r -> r.t1().statusCode() >= 400 && r.t1().statusCode() <= 499)
              .isAttemptAvailable(c -> Duration.between(c.enqueueTime(), c.now()).compareTo(Duration.ofHours(5)) < 0)
              .backoffAlgorithm(c -> new DelaySpecification(Duration.ofSeconds(10), Duration.ofMinutes(10), Duration.ofHours(5), 1.5).calculateDelay(c.attemptNumber()))
              .build("ExchangeRollbackOnAccepted", UUID.fromString("fd4959b4-5c14-4b7a-8ea5-b559b94f803c"))
          )
          .rollbackOnUnknownModel(HttpOutboxRequest.atLeastOnce()
              .requestPayloadType(DataType.none())
              .responsePayloadType(DataType.none())
              .repeatMessageCreator((_, o) -> o)
              .forwarder(new NettyHttpClient(new NettyHttpClientBuilder().build()))
              .contentParser(_ -> valid(null))
              .isAccepted(r -> r.statusCode() >= 200 && r.statusCode() <= 299)
              .isFailureTransient(r -> r.statusCode() >= 500 && r.statusCode() <= 599)
              .isRejectedByInvalidResponse(r -> r.t1().statusCode() >= 400 && r.t1().statusCode() <= 499)
              .isFailureByInvalidResponseTransient(r -> r.t1().statusCode() >= 400 && r.t1().statusCode() <= 499)
              .isAttemptAvailable(c -> Duration.between(c.enqueueTime(), c.now()).compareTo(Duration.ofHours(5)) < 0)
              .backoffAlgorithm(c -> new DelaySpecification(Duration.ofSeconds(10), Duration.ofMinutes(10), Duration.ofHours(5), 1.5).calculateDelay(c.attemptNumber()))
              .build("ExchangeRollbackOnUnknown", UUID.fromString("ec3848a3-4b03-3a69-7d94-a448a83e792b"))
          )
          .build("Exchange", UUID.fromString("a3778c63-144f-4748-9e8b-cc10ee20db3f"));
      AtMostOnceWithRollbackDispatcher<Void, Void, Void> dispatcher = AtMostOnceWithRollbackDispatcher.of(
          Exchange,
          (Void _) -> requestMessage(path),
          (_, d) -> Mono.just(requestMessage(d.t2().uri().getPath() + "/rollback"))
      );

      Map<State, List<TransitionModel<?, ?>>> processTransitions = Map.of(
          Begin, List.of(
              onEvent(processStarted).to(States.WaitingForResponse)
                  .assemble(DispatchContext::of)
                  .trigger(dispatcher.requestDispatched()).withReactive(d -> dispatcher.input(d, null)).on(dispatcher.outbox()).identifiedBy(newEntityId())
                  .reversible(
                      assemble((log, _) -> log.one(processStarted))
                          .trigger(dispatcher.rollbackDispatched()).on(dispatcher.outbox()).identifiedBy(d -> entityId(d))
                  )
                  .output(d -> d.t2().accepted().event().entityId())
          ),
          States.WaitingForResponse, List.of(
              onEvent(processSucceeded).to(States.Done).output(),
              onEvent(processFailed).to(States.Done).output(),
              onEvent(responseMissed).to(States.Done).output(),
              onEvent(outcomeUndetermined).to(States.Done).output()
          ),
          States.Done, List.of()
      );

      var machine = stateMachine(
          process,
          processTransitions,
          dispatcher.transitions()
      );
      return new ProcessModel(process, processStarted, processSucceeded, processFailed, responseMissed, outcomeUndetermined, Exchange, machine, path);
    }

  }

  record ProcessModelWithoutRollback(
      EntityModel process,
      EventType<Void, Void> processStarted,
      EventType<Void, Void> processSucceeded,
      EventType<Void, Void> processFailed,
      StateMachine machine,
      String path
  ) {
    static ProcessModelWithoutRollback create() {
      EntityModel process = EntityModel.of("Process", UUID.randomUUID());
      EventType<Void, Void> processStarted = BasicEventType.of("Process started", UUID.randomUUID());
      EventType<Void, Void> processSucceeded = BasicEventType.of("Process succeeded", UUID.randomUUID());
      EventType<Void, Void> processFailed = BasicEventType.of("Process failed", UUID.randomUUID());
      String path = "/" + UUID.randomUUID();
      HttpOutboxRequest<Void> Exchange = HttpOutboxRequest.atMostOnce()
          .requestPayloadType(DataType.none())
          .responsePayloadType(DataType.none())
          .forwarder(new NettyHttpClient(new NettyHttpClientBuilder().build()))
          .processModel(process)
          .onPeerUnavailable(processFailed)
          .onMissingResponse(processFailed)
          .onInvalidResponseRejection(processFailed)
          .onInvalidResponseUnknown(processFailed)
          .contentParser(_ -> valid(null))
          .onSuccess(processSucceeded)
          .onFailure(processFailed)
          .isAccepted(r -> r.statusCode() >= 200 && r.statusCode() <= 299)
          .isRejected(r -> r.statusCode() >= 400 && r.statusCode() <= 499)
          .isRejectedByInvalidResponse(r -> r.t1().statusCode() >= 400 && r.t1().statusCode() <= 499)
          .build("ExchangeWithoutRollback", UUID.fromString("a3778c63-144f-4748-9e8b-cc10ee20db3f"));

      Map<State, List<TransitionModel<?, ?>>> processTransitions = Map.of(
          Begin, List.of(
              onEvent(processStarted).to(States.WaitingForResponse)
                  .trigger(Exchange.requestDispatched()).with(_ -> requestMessage(path)).on(Exchange).identifiedBy(newEntityId())
                  .output()
          ),
          States.WaitingForResponse, List.of(
              onEvent(processSucceeded).to(States.Done)
                  .output(),
              onEvent(processFailed).to(States.Done)
                  .output()
          )
      );

      var machine = stateMachine(
          process,
          processTransitions,
          List.of(Exchange)
      );
      return new ProcessModelWithoutRollback(process, processStarted, processSucceeded, processFailed, machine, path);
    }

  }

  record ProcessModelWithDefaultInvalidResponseRejection(
      EntityModel process,
      EventType<Void, Void> processStarted,
      StateMachine machine,
      String path
  ) {
    static ProcessModelWithDefaultInvalidResponseRejection create() {
      EntityModel process = EntityModel.of("Process", UUID.randomUUID());
      EventType<Void, Void> processStarted = BasicEventType.of("Process started", UUID.randomUUID());
      String path = "/" + UUID.randomUUID();
      HttpOutboxRequest<Void> Exchange = HttpOutboxRequest.atMostOnce()
          .requestPayloadType(DataType.none())
          .responsePayloadType(DataType.none())
          .forwarder(new NettyHttpClient(new NettyHttpClientBuilder().build()))
          .processModel(process)
          .contentParser(_ -> invalid("Invalid response"))
          .isAccepted(_ -> false)
          .isRejected(_ -> false)
          .isRejectedByInvalidResponse(_ -> true)
          .build("ExchangeWithDefaultInvalidResponse", UUID.fromString("a3778c63-144f-4748-9e8b-cc10ee20db3f"));

      Map<State, List<TransitionModel<?, ?>>> processTransitions = Map.of(
          Begin, List.of(
              onEvent(processStarted).to(States.WaitingForResponse)
                  .trigger(Exchange.requestDispatched()).with(_ -> requestMessage(path)).on(Exchange).identifiedBy(newEntityId())
                  .output()
          ),
          States.WaitingForResponse, List.of(
              onEvent(InvalidResponseReceived).to(States.Done)
                  .assembleInput()
                  .output(d -> d)
          ),
          States.Done, List.of()
      );

      var machine = stateMachine(
          process,
          processTransitions,
          List.of(Exchange)
      );
      return new ProcessModelWithDefaultInvalidResponseRejection(process, processStarted, machine, path);
    }

  }

  record ProcessModelWithDefaultInvalidResponseUnknown(
      EntityModel process,
      EventType<Void, Void> processStarted,
      StateMachine machine,
      String path
  ) {
    static ProcessModelWithDefaultInvalidResponseUnknown create() {
      EntityModel process = EntityModel.of("Process", UUID.randomUUID());
      EventType<Void, Void> processStarted = BasicEventType.of("Process started", UUID.randomUUID());
      String path = "/" + UUID.randomUUID();
      HttpOutboxRequest<Void> Exchange = HttpOutboxRequest.atMostOnce()
          .requestPayloadType(DataType.<Void>unknown())
          .responsePayloadType(DataType.unknown())
          .forwarder(new NettyHttpClient(new NettyHttpClientBuilder().build()))
          .processModel(process)
          .contentParser(_ -> invalid("Invalid response"))
          .isAccepted(_ -> false)
          .isRejected(_ -> false)
          .isRejectedByInvalidResponse(_ -> false)
          .build("ExchangeWithDefaultInvalidResponseUnknown", UUID.fromString("a3778c63-144f-4748-9e8b-cc10ee20db3f"));

      Map<State, List<TransitionModel<?, ?>>> processTransitions = Map.of(
          Begin, List.of(
              onEvent(processStarted).to(States.WaitingForResponse)
                  .trigger(Exchange.requestDispatched()).with(_ -> requestMessage(path)).on(Exchange).identifiedBy(newEntityId())
                  .output()
          ),
          States.WaitingForResponse, List.of(
              onEvent(InvalidResponseReceived).to(States.Done)
                  .assembleInput()
                  .output(d -> d)
          ),
          States.Done, List.of()
      );

      var machine = stateMachine(
          process,
          processTransitions,
          List.of(Exchange)
      );
      return new ProcessModelWithDefaultInvalidResponseUnknown(process, processStarted, machine, path);
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
          .requestPayloadType(DataType.<Void>unknown())
          .responsePayloadType(DataType.unknown())
          .forwarder(new NettyHttpClient(new NettyHttpClientBuilder().build()))
          .processModel(process)
          .contentParser(_ -> valid((Void) null))
          .isAccepted(_ -> true)
          .isRejected(_ -> false)
          .isRejectedByInvalidResponse(_ -> false)
          .build("ExchangeWithDefaultMissingResponse", UUID.fromString("a3778c63-144f-4748-9e8b-cc10ee20db3f"));
      return new ProcessModelWithDefaultMissingResponse(process, exchange);
    }

  }

}
