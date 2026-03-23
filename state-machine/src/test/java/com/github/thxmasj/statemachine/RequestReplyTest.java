package com.github.thxmasj.statemachine;

import static com.github.thxmasj.statemachine.BuiltinEntities.InboxExchange.AcceptedRequest;
import static com.github.thxmasj.statemachine.BuiltinEntities.InboxExchange.MessageId;
import static com.github.thxmasj.statemachine.BuiltinEntities.InboxExchange.Request;
import static com.github.thxmasj.statemachine.BuiltinEntities.InboxExchange.RollbackResponse;
import static com.github.thxmasj.statemachine.BuiltinEntities.InboxExchange.State.Requested;
import static com.github.thxmasj.statemachine.BuiltinEntities.InboxExchange.State.Responded;
import static com.github.thxmasj.statemachine.BuiltinEventTypes.Rollback;
import static com.github.thxmasj.statemachine.EntitySelector.CreationMode.CreateIfNotExists;
import static com.github.thxmasj.statemachine.EntitySelector.entityId;
import static com.github.thxmasj.statemachine.EntitySelector.entityIdFromSession;
import static com.github.thxmasj.statemachine.EntitySelector.newEntityId;
import static com.github.thxmasj.statemachine.EntitySelector.secondaryId;
import static com.github.thxmasj.statemachine.RequestReplyTest.Entities.Lamp;
import static com.github.thxmasj.statemachine.RequestReplyTest.Queues.DeviceListener;
import static com.github.thxmasj.statemachine.RequestReplyTest.States.Off;
import static com.github.thxmasj.statemachine.RequestReplyTest.States.On;
import static com.github.thxmasj.statemachine.RequestReplyTest.States.Processing;
import static com.github.thxmasj.statemachine.RequestReplyTest.States.Unreachable;
import static com.github.thxmasj.statemachine.TransitionModelBuilder.WithEvent.onEvent;
import static com.github.thxmasj.statemachine.TransitionModelBuilder.WithFilter.Alternative.then;
import static com.github.thxmasj.statemachine.TransitionModelBuilder.assemble;
import static com.github.thxmasj.statemachine.Tuples.tuple;
import static com.github.thxmasj.statemachine.message.JsonValidator.json;
import static com.github.thxmasj.statemachine.message.http.HttpRequestMessage.Method.DELETE;
import static com.github.thxmasj.statemachine.message.http.HttpRequestMessage.Method.POST;
import static com.github.thxmasj.statemachine.message.http.HttpRequestMessage.Method.PUT;
import static java.util.Objects.requireNonNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.thxmasj.statemachine.BasicEventType.Rollback.Data;
import com.github.thxmasj.statemachine.BuiltinEntities.InboxExchange;
import com.github.thxmasj.statemachine.BuiltinEntities.InboxExchange.RequestType;
import com.github.thxmasj.statemachine.EventTrigger.EventSpec;
import com.github.thxmasj.statemachine.EventType.DataType;
import com.github.thxmasj.statemachine.IncomingResponseValidator.Result;
import com.github.thxmasj.statemachine.IncomingResponseValidator.Result.Status;
import com.github.thxmasj.statemachine.TransitionModelBuilder.TransitionModel;
import com.github.thxmasj.statemachine.TransitionModelBuilder.WithFilter.Alternative;
import com.github.thxmasj.statemachine.Tuples.Tuple2;
import com.github.thxmasj.statemachine.Tuples.Tuple3;
import com.github.thxmasj.statemachine.http.NettyHttpClient;
import com.github.thxmasj.statemachine.http.NettyHttpClientBuilder;
import com.github.thxmasj.statemachine.message.http.Created;
import com.github.thxmasj.statemachine.message.http.HttpMessageParser;
import com.github.thxmasj.statemachine.message.http.HttpRequestMessage;
import com.github.thxmasj.statemachine.message.http.HttpRequestMessage.Method;
import com.github.thxmasj.statemachine.message.http.HttpResponseMessage;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

public class RequestReplyTest {

  private static final long PROCESSING_TIMEOUT = 2000;
  private static final long LONG_EXTERNAL_PROCESSING = PROCESSING_TIMEOUT + 1000;

  enum States implements State {
    On,
    Off,
    Processing {
      @Override public Optional<Timeout> timeout() {
        return Optional.of(new Timeout(Duration.ofMillis(PROCESSING_TIMEOUT), new InputEvent<>(Rollback, new Data(-1, "Processing timed out after " + PROCESSING_TIMEOUT + " ms"))));
      }
    },
    Unreachable
  }

  private static final Random random = new Random();

  enum Entities implements EntityModel {
    Lamp {
      @Override
      public UUID id() {
        return UUID.fromString("59d3158c-7b2a-4312-a293-325858c2673f");
      }

      @Override
      public State initialState() {
        return Off;
      }

    }
  }

  private static Map<State, List<TransitionModel<?, ?>>> lampTransitions() {
    return Map.of(
        On, List.of(
            onEvent(InternalProcessing).to(Off).assembleInput().output(d -> d),
            onEvent(SwitchOff).to(Off).output(),
            onEvent(Cancel).toSelf()
                .assemble(c -> tuple(c.input().data(), c.log().entityId()))
                .trigger(AcceptedRequest).with(Tuple2::t2).on(inboxExchange).identifiedBy(entityIdFromSession())
                .output(d -> d.t1().t1()),
            onEvent(ZeroProcessing).toSelf()
                .assemble(c -> tuple(c.input().data(), c.log().entityId()))
                .trigger(AcceptedRequest).with(Tuple2::t2).on(inboxExchange).identifiedBy(entityIdFromSession())
                .output(d -> d.t1().t1())
        ),
        Off, List.of(
            onEvent(InternalProcessing).to(On)
                .assemble(c -> c.log().entityId())
                .trigger(new ProcessRequest(0)).with(_ -> null).to(DeviceListener).guaranteed()
                .trigger(InternalProcessResponse).with(d -> tuple(d, "Light is on! " + random.nextLong())).on(inboxExchange).identifiedBy(entityIdFromSession())
                .output(),
            onEvent(ComplexInternalProcessing).to(Processing)
                .assemble(d -> d)
                .trigger(ComplexInternalProcessingDone).on(Lamp).identifiedBy(entityIdFromSession())
                .output(),
            onEvent(ExternalProcessing).to(Processing)
                .assembleInput()
                .trigger(new ProcessRequest(0)).with(_ -> null).to(DeviceListener).guaranteed().responseValidator((_, _, _, _) -> Mono.just(new Result(Status.Ok, "Ok", new InputEvent<>(ExternalProcessingDone, null))))
                .output(),
            onEvent(LongExternalProcessing).to(Processing)
                .assembleInput()
                .trigger(new ProcessRequest(LONG_EXTERNAL_PROCESSING)).with(_ -> null).to(DeviceListener).guaranteed().responseValidator((_, _, _, _) -> Mono.just(new Result(Status.Ok, "Ok", new InputEvent<>(ExternalProcessingDone, null))))
                .reversible(
                    assemble((log, rollbackType) -> "")
                        // NB: Response in request/reply session not possible with reversals triggered by the resolver
                        //.trigger(ComplexInternalProcessResponse).with(_ -> "Failed to switch on light! :(((").on(inboxExchange).identifiedBy(entityIdFromSession())
                        .trigger(new ProcessRequest(0)).with(_ -> null).to(DeviceListener).guaranteed().responseValidator((_, _, _, _) -> Mono.just(new Result(Status.Ok, "Ok", new InputEvent<>(ExternalProcessingDone, null))))
                        .output()
                )
                .output(),
            onEvent(SwitchOn).to(On).output()
        ),
        Processing, List.of(
            onEvent(Rollback).toSelf().assembleInput().output(d -> d),
            onEvent(ComplexInternalProcessingDone).to(On)
                .trigger(ComplexInternalProcessResponse).with(_ -> "Phew! Light is switched on! " + random.nextLong()).on(inboxExchange).identifiedBy(entityIdFromSession())
                .output(),
            onEvent(ExternalProcessingDone).to(On)
                .trigger(ExternalProcessResponse).with(_ -> "Light is externally switched on! " + random.nextLong()).on(inboxExchange).identifiedBy(entityIdFromSession())
                .output()
        ),
        Unreachable, List.of(
            onEvent(EventThatIsAlwaysRejected).toSelf().output()
        )
    );
  }

  static RequestType InternalProcessRequest = new RequestType("InternalProcessRequest", UUID.fromString("5085d340-1856-43fc-914b-810654efd12b"));
  static RequestType ZeroProcessRequest = new RequestType("ZeroProcessRequest", UUID.fromString("28d292b3-bd62-4d46-a86e-073bfdd84963"));
  static RequestType UnknownProcessRequest = new RequestType("UnknownProcessRequest", UUID.fromString("4c69054a-55f2-4f2c-ba2a-a3323b034204"));
  static RequestType ComplexInternalProcessRequest = new RequestType("ComplexInternalProcessRequest", UUID.fromString("144247d4-6ae1-4b6e-9fea-965847421928"));
  static RequestType ExternalProcessRequest = new RequestType("ExternalProcessRequest", UUID.fromString("a0c01f4b-31b0-444a-b77b-03e6baf5c967"));
  static RequestType LongExternalProcessRequest = new RequestType("LongExternalProcessRequest", UUID.fromString("429a97a0-a5cc-4580-a535-f4b7bf7097a3"));
  static RequestType RequestWhichIsRejected = new RequestType("RequestWhichIsRejected", UUID.fromString("fe4cbf76-d654-499f-8be2-98c44adb93e1"));
  static RequestType CancelRequest = new RequestType("CancelRequest", UUID.fromString("4419cf8f-7508-41ca-8baf-74ebab08f57c"));
  static EventType<Tuple2<EntityId, String>, Void> InternalProcessResponse = BasicEventType.of(
      "InternalProcessResponse",
      UUID.fromString("20234808-de8c-404d-8795-7a7854676166"),
      new DataType<>(new TypeReference<Tuple2<EntityId, String>>() {}, EntityId.class, String.class),
      Void.class
  );
  static EventType<String, Void> ComplexInternalProcessResponse = BasicEventType.of("ComplexInternalProcessResponse", UUID.fromString("b7450893-f036-4647-9a1b-accbd19fa7c2"), String.class, Void.class);
  static EventType<String, Void> ExternalProcessResponse = BasicEventType.of("ExternalProcessResponse", UUID.fromString("b75ca624-010e-43ab-ae38-19f62289f4ea"), String.class, Void.class);

  record InternalProcess(String a, int b) {}

  static EventType<Tuple2<HttpRequestMessage, InternalProcess>, HttpRequestMessage> ValidInternalProcessRequest = BasicEventType.of(
      "ValidInternalProcessRequest",
      UUID.fromString("46b0211e-f583-49b3-a6e7-8d13742e0260"),
      new DataType<>(new TypeReference<Tuple2<HttpRequestMessage, InternalProcess>>() {}, HttpRequestMessage.class, InternalProcess.class),
      new DataType<>(HttpRequestMessage.class)
  );
  static EventType<Tuple2<HttpRequestMessage, String>, HttpRequestMessage> InvalidInternalProcessRequest = BasicEventType.of(
      "InvalidInternalProcessRequest",
      UUID.fromString("6f00b6ba-b0e5-46a6-ad85-64eec4891b69"),
      new DataType<>(new TypeReference<Tuple2<HttpRequestMessage, String>>() {}, HttpRequestMessage.class, String.class),
      new DataType<>(HttpRequestMessage.class)
  );

  public record Zero(String value) {}

  static EventType<Zero, Zero>
      ZeroProcessing = BasicEventType.of("ZeroProcessing", UUID.fromString("aa70041a-a68f-4bcd-831f-5029eb329a05"), Zero.class);
  static EventType<Void, Void>
      EventThatIsAlwaysRejected = BasicEventType.of("EventThatIsAlwaysRejected", UUID.fromString("bd98a725-ac60-4db4-b7db-ce08a295ffca")),
      InternalProcessing = BasicEventType.of("InternalProcessing", UUID.fromString("5909336e-cc75-4c58-902f-fb68f08b0caa")),
      ComplexInternalProcessing = BasicEventType.of("ComplexInternalProcessing", UUID.fromString("16c1792d-9fb7-4f91-b55b-b59e2b00bad8")),
      ComplexInternalProcessingDone = BasicEventType.of("ComplexInternalProcessingDone", UUID.fromString("88c87c85-7778-4171-bc75-702f9e60b00e")),
      ExternalProcessingDone = BasicEventType.of("ExternalProcessingDone", UUID.fromString("464fca06-f872-4e38-a167-5550f5247310"));
  static EventType<BasicEventType.Rollback.Data, BasicEventType.Rollback.Data>
      Cancel = new BasicEventType.Cancel("Cancel", UUID.fromString("d94c29c8-f113-4dee-921f-1a8e58f916f4"));
  static EventType<String, String>
      ExternalProcessing = BasicEventType.of("ExternalProcessing", UUID.fromString("12a97e70-58cc-46a8-aed9-f867cfc7375f"), String.class),
      LongExternalProcessing = BasicEventType.of("LongExternalProcessing", UUID.fromString("6ebcbbec-5bbc-4c05-96fd-41ad0bbc097a"), String.class);
  static EventType<Void, Void>
      SwitchOn = BasicEventType.of("SwitchOn", UUID.fromString("5e9a8a9d-6a21-41cf-82dc-857fe1e4c4e0")),
      SwitchOff = BasicEventType.of("SwitchOff", UUID.fromString("8e1483c8-b649-43b8-b352-5094a94c0dad"))
          ;

  static InboxExchange inboxExchange = new InboxExchange() {

    @Override
    protected Map<Predicate<HttpRequestMessage>, Alternative<HttpRequestMessage, ?, ?>> routes() {
      return Map.of(
          m -> m.requestLine().matches("PUT .*/zero/.*"),
          then(onEvent(ZeroProcessRequest).to(Requested)
              .assemble((input, _) -> tuple(
                  input.data(),
                  UUID.fromString(requireNonNull(from(input.data().requestLine(), "PUT .*/zero/(.*)", 1)))
              ))
              .newIdentifier(MessageId, d -> new MessageId("x", ZeroProcessRequest.id() + "/" + d.t2().toString()))
              .trigger(ZeroProcessing).with(_ -> new Zero("Hey!")).on(Lamp)
              .identifiedBy(entityId(d -> d.t1().t2()))
              .output(d -> d.t1().t1().t1())
          ),
          m -> m.requestLine().matches("PUT .*/unknown/.*"),
          then(onEvent(UnknownProcessRequest).to(Requested)
              .assembleInput()
              .trigger(InternalProcessing).on(Lamp).identifiedBy(entityId(UUID.randomUUID()))
              .when(d -> d.t2().isUnknownId()).then(
                  onEvent(UnknownProcessRequest).to(Requested)
                      .assemble(d -> d.log().entityId())
                      .trigger(InternalProcessing).on(Lamp).identifiedBy(entityId(UUID.randomUUID()))
                      .trigger(AcceptedRequest).with(Tuple2::t1).on(inboxExchange).identifiedBy(entityIdFromSession())
                      .output(),
                  Tuple2::t1
              )
              .output()
          ),
          m -> m.requestLine().matches("PUT .*/internal/.*"),
          then(onEvent(InternalProcessRequest).to(Requested)
              .assemble((input, log) -> tuple(
                  json(input.data().body(), InternalProcess.class),
                  input.data(),
                  log.entityId()
              ))
              .when(d -> d.t1().isValid()).then(
                  onEvent(ValidInternalProcessRequest).to(Requested)
                      .assembleInput()
                      .newIdentifier(
                          MessageId,
                          d -> new MessageId("x", from(d.t1().requestLine(), "PUT .*/internal/(.*)", 1))
                      )
                      .trigger(InternalProcessing).on(Lamp).identifiedBy(newEntityId())
                      .newIdentifier(
                          EventReference,
                          d -> new EventReference(d.t2().accepted().entityId(), d.t2().accepted().event().eventNumber())
                      )
                      .output(d -> d.t1().t1().t1().t1()),
                  d -> tuple(d.t2(), d.t1().value())
              )
              .when(d -> d.t1().isInvalid()).then(
                  onEvent(InvalidInternalProcessRequest).to(Requested)
                      .assembleInput()
                      .trigger(InvalidRequest)
                      .with(Tuple2::t2)
                      .on(this)
                      .identifiedBy(entityIdFromSession())
                      .output(d -> d.t1().t1()),
                  d -> tuple(d.t2(), "Invalid request")
              )
              .output(Tuple3::t2)
          ),
          m -> m.requestLine().matches("PUT .*/complexinternal/.*"),
          then(onEvent(ComplexInternalProcessRequest).to(Requested)
              .assemble((input, log) -> tuple(input.data(), log.entityId()))
              .newIdentifier(
                  MessageId,
                  d -> new MessageId("x", from(d.t1().requestLine(), "PUT .*/complexinternal/(.*)", 1))
              )
              .trigger(ComplexInternalProcessing).on(Lamp).identifiedBy(newEntityId())
              .newIdentifier(
                  EventReference,
                  d -> new EventReference(d.t2().accepted().entityId(), d.t2().accepted().event().eventNumber())
              )
              .output(d -> d.t1().t1().t1().t1())
          ),
          m -> m.requestLine().matches("PUT .*/external/.*"),
          then(onEvent(ExternalProcessRequest).to(Requested)
              .assemble((input, log) -> tuple(input.data(), log.entityId()))
              .newIdentifier(
                  MessageId,
                  d -> new MessageId("x", from(d.t1().requestLine(), "PUT .*/external/(.*)", 1))
              )
              .trigger(ExternalProcessing).on(Lamp).identifiedBy(newEntityId())
              .newIdentifier(
                  EventReference,
                  d -> new EventReference(d.t2().accepted().entityId(), d.t2().accepted().event().eventNumber())
              )
              .output(d -> d.t1().t1().t1().t1())
          ),
          m -> m.requestLine().matches("PUT .*/long-external/.*"),
          then(onEvent(LongExternalProcessRequest).to(Requested)
              .assemble((input, log) -> tuple(input.data(), log.entityId()))
              .newIdentifier(
                  MessageId,
                  d -> new MessageId("x", from(d.t1().requestLine(), "PUT .*/long-external/(.*)", 1))
              )
              .trigger(LongExternalProcessing).on(Lamp).identifiedBy(newEntityId())
              .newIdentifier(
                  EventReference,
                  d -> new EventReference(d.t2().accepted().entityId(), d.t2().accepted().event().eventNumber())
              )
              .output(d -> d.t1().t1().t1().t1())
          ),
          m -> m.requestLine().matches("PUT .*/rejected/.*"),
          then(onEvent(RequestWhichIsRejected).to(Requested)
              .assemble((input, log) -> tuple(input.data(), log.entityId()))
              .newIdentifier(
                  MessageId,
                  d -> new MessageId("x", from(d.t1().requestLine(), "PUT .*/rejected/(.*)", 1))
              )
              .trigger(EventThatIsAlwaysRejected).on(Lamp).identifiedBy(newEntityId())
              .newIdentifier(
                  EventReference,
                  d -> new EventReference(d.t2().accepted().entityId(), d.t2().accepted().event().eventNumber())
              )
              .output(d -> d.t1().t1().t1().t1())
          ),
          m -> m.requestLine().matches("PUT .*/oneway/.*"),
          then(onEvent(ExternalProcessRequest).to(Requested)
              .assemble((input, log) -> tuple(input.data(), log.entityId()))
              .output(Tuple2::t1)
          ),
          // RollbackRequest arrives before ToggleRequest. Create message id to block ToggleRequest if it comes
          m -> m.requestLine().matches("DELETE .*/lamps/messages/.*"),
          then(initialRollbackRequest(d -> new MessageId(
                  "x",
                  from(d.requestLine(), "DELETE .*/lamps/messages/(.*)", 1)
          ))),
          m -> m.requestLine().matches("DELETE .*/internal/.*"),
          then(
              onEvent(CancelRequest).to(Requested)
                  .assemble((input, _) -> tuple(
                      input.data(),
                      UUID.fromString(requireNonNull(from(input.data().requestLine(), "DELETE .*/internal/(.*)", 1)))
                  ))
                  .newIdentifier(MessageId, d -> new MessageId("x", CancelRequest.id() + "/" + d.t2().toString()))
                  .trigger(Cancel).with(_ -> new Data(0, "Cancel")).on(Lamp)
                  .identifiedBy(entityId(d -> d.t1().t2()))
                  .output(d -> d.t1().t1().t1())
          ));
    }

    @Override
    protected List<TransitionModel<?, ?>> responseTransitions() {
      return List.of(
          onEvent(InternalProcessResponse).to(Responded)
              .assemble(c -> tuple(c.input().data(), c.timestamp(), c.correlationId()))
              .when(_ -> true).then(
                  onEvent(Response).to(Responded).assembleInput().output(d -> d),
                  d -> createResponseMessage(new Created(), d.t1().t1(), d.t3(), d.t2(), d.t1().t2())
              )
              .output(),
          //onResponseEvent(InternalProcessResponse, new Created()),
          onResponseEvent(ComplexInternalProcessResponse, new Created()),
          onResponseEvent(ExternalProcessResponse, new Created())
      );
    }

    @Override
    protected Predicate<HttpRequestMessage> rollbackPredicate() {
      return m -> m.requestLine().matches("DELETE .*/lamps/messages/.*");
    }

    @Override
    protected EntityModel rollbackEntity() {
      return Lamp;
    }

  };

  enum Queues implements OutboxQueue {
    DeviceListener {
      @Override
      public UUID id() {
        return UUID.fromString("ca9a8d7a-8342-42e1-ab58-62c61f8d4719");
      }
    };
  }

  static class ProcessRequest implements OutgoingRequestCreator<Void> {

    private final long processingTime;

    ProcessRequest(long processingTime) {this.processingTime = processingTime;}

    @Override
    public HttpRequestMessage create(Void data, Context context) {
      return new HttpRequestMessage(
          POST,
          URI.create("http://localhost:" + server.getAddress().getPort() + "/process/" + processingTime)
      );
    }

    @Override
    public UUID id() {
      return UUID.fromString("e0eadb64-224a-480c-b1a2-aab29927fb7e");
    }

    @Override
    public boolean equals(Object obj) {
      return obj instanceof ProcessRequest other && id().equals(other.id());
    }

    @Override
    public int hashCode() {
      return id().hashCode();
    }
  }

  private static StateMachine stateMachine;
  private static HttpServer server;

  @BeforeAll
  public static void setUp() throws IOException {
    server = HttpServer.create(new InetSocketAddress(0), 1);
    server.createContext(
        "/process/",
        exchange -> {
          long processTime = Long.parseLong(exchange.getRequestURI().getPath().substring("/process/".length()));
          if (processTime > 0) {
            System.out.println("External service: Processing for " + processTime + " ms...");
            try {
              Thread.sleep(processTime);
            } catch (InterruptedException e) {
              throw new RuntimeException(e);
            }
          }
          exchange.sendResponseHeaders(200, 0);
          exchange.getResponseBody().close();
          exchange.close();
        }
    );
    server.createContext(
        "/long-process",
        exchange -> {
          try {
            Thread.sleep(10000);
          } catch (InterruptedException e) {
            throw new RuntimeException(e);
          }
          exchange.sendResponseHeaders(200, 0);
          exchange.getResponseBody().close();
          exchange.close();
        }
    );
    server.start();
    Map<EntityModel, Map<State, List<TransitionModel<?, ?>>>> transitions = new HashMap<>();
    transitions.put(Lamp, lampTransitions());
    transitions.put(inboxExchange, inboxExchange.transitions());
    //noinspection SwitchStatementWithTooFewBranches
    stateMachine = Init.stateMachine(
        transitions,
        queue -> switch (queue) {
          case DeviceListener -> new NettyHttpClient(new NettyHttpClientBuilder().build());
          default -> null;
        }
    );
  }

  @Test
  public void whenInternalProcessRequestThenInternalProcessResponse() throws JsonProcessingException {
    String messageId = UUID.randomUUID().toString();
    Event<?> responseEvent = onRequest(PUT, URI.create("/internal/" + messageId), new ObjectMapper().writeValueAsString(new InternalProcess("Hello", 1)));
    HttpResponseMessage response = HttpMessageParser.parseResponse(responseEvent.data());
    assertEquals(201, response.statusCode());
    assertTrue(ProblemDetail.parse(response.body()).detail().startsWith("Light is on!"));
  }

  @Test
  public void whenZeroingInternalProcessRequestThenAcceptedResponse() throws JsonProcessingException {
    String messageId = UUID.randomUUID().toString();
    Event<?> responseEvent = onRequest(PUT, URI.create("/internal/" + messageId), new ObjectMapper().writeValueAsString(new InternalProcess("Hello", 1)));
    HttpResponseMessage response = HttpMessageParser.parseResponse(responseEvent.data());
    assertEquals(201, response.statusCode());
    ProblemDetail problemDetail = ProblemDetail.parse(response.body());
    assertTrue(problemDetail.detail().startsWith("Light is on!"));
    UUID entityId = UUID.fromString(problemDetail.entityId());

    Event<?> zeroResponseEvent = onRequest(PUT, URI.create("/zero/" + entityId), null);
    HttpResponseMessage zeroResponse = HttpMessageParser.parseResponse(zeroResponseEvent.data());
    assertEquals(201, zeroResponse.statusCode());
    assertTrue(ProblemDetail.parse(zeroResponse.body()).detail().startsWith("Created"));

  }

  @Test
  public void whenCancelInternalProcessRequestThen() throws JsonProcessingException {
    String messageId = UUID.randomUUID().toString();
    Event<?> responseEvent = onRequest(PUT, URI.create("/internal/" + messageId), new ObjectMapper().writeValueAsString(new InternalProcess("Hello", 1)));
    HttpResponseMessage response = HttpMessageParser.parseResponse(responseEvent.data());
    assertEquals(201, response.statusCode());
    ProblemDetail problemDetail = ProblemDetail.parse(response.body());
    assertTrue(problemDetail.detail().startsWith("Light is on!"));
    UUID entityId = UUID.fromString(problemDetail.entityId());
    Event<?> cancelResponseEvent = onRequest(DELETE, URI.create("/internal/" + entityId), null);
    HttpResponseMessage cancelResponse = HttpMessageParser.parseResponse(cancelResponseEvent.data());
    assertEquals(201, cancelResponse.statusCode());
  }

  @Test
  public void whenComplexInternalProcessRequestThenComplexInternalProcessResponse() throws JsonProcessingException {
    String messageId = UUID.randomUUID().toString();
    Event<?> responseEvent = onRequest(PUT, URI.create("/complexinternal/" + messageId), "Hello!");
    HttpResponseMessage response = HttpMessageParser.parseResponse(responseEvent.data());
    assertEquals(201, response.statusCode());
    assertTrue(ProblemDetail.parse(response.body()).detail().startsWith("Phew! Light is switched on!"));
  }

  @Test
  public void whenRequestIsRejectedDownstreamThenUnprocessableEntityResponse() throws JsonProcessingException {
    String messageId = UUID.randomUUID().toString();
    Event<?> responseEvent = onRequest(PUT, URI.create("/rejected/" + messageId), "Hello!");
    HttpResponseMessage response = HttpMessageParser.parseResponse(responseEvent.data());
    assertEquals(422, response.statusCode());
    ProblemDetail pd = ProblemDetail.parse(response.body());
    assertEquals("InboxExchange does not accept Request: Nested change rejected: EventThatIsAlwaysRejected on Lamp was rejected", pd.detail());
    assertEquals(422, pd.status());
  }

  @Test
  public void whenExternalProcessRequestThenExternalProcessResponse() throws JsonProcessingException {
    String messageId = UUID.randomUUID().toString();
    Event<?> responseEvent = onRequest(PUT, URI.create("/external/" + messageId), "Hello!");
    HttpResponseMessage response = HttpMessageParser.parseResponse(responseEvent.data());
    assertEquals(201, response.statusCode());
    assertTrue(ProblemDetail.parse(response.body()).detail().startsWith("Light is externally switched on!"));
  }

  @Test
  public void whenUnknownIdHandledThenOkResponse() throws JsonProcessingException {
    String messageId = UUID.randomUUID().toString();
    Event<?> responseEvent = onRequest(PUT, URI.create("/unknown/" + messageId), "Hello!");
    HttpResponseMessage response = HttpMessageParser.parseResponse(responseEvent.data());
    assertEquals(201, response.statusCode());
    assertEquals("Created", ProblemDetail.parse(response.body()).detail());
  }

  @Test
  @Disabled("Sync response (in request/reply session) not possible with reversals triggered by the resolver")
  public void whenLongExternalProcessRequestThenRollback() throws JsonProcessingException {
    String messageId = UUID.randomUUID().toString();
    Event<?> responseEvent = onRequest(PUT, URI.create("/long-external/" + messageId), "Hello!");
    HttpResponseMessage response = HttpMessageParser.parseResponse(responseEvent.data());
    assertEquals(201, response.statusCode());
    assertTrue(ProblemDetail.parse(response.body()).detail().startsWith("Light is externally switched on!"));
  }

  @Test
  @Disabled("TODO")
  public void whenOneWayRequestThenTimeout() throws JsonProcessingException {
    Event<?> responseEvent = onRequest(PUT, URI.create("/oneway/xxx"), "Hello!");
    HttpResponseMessage response = HttpMessageParser.parseResponse(responseEvent.data());
    assertEquals(201, response.statusCode());
    assertTrue(ProblemDetail.parse(response.body()).detail().startsWith("Light is on!"));
  }

  @Test
  public void whenDuplicateRequestThenRespondWithSameResponse() throws JsonProcessingException {
    String messageId = UUID.randomUUID().toString();
    InternalProcess body = new InternalProcess("Static", 0);
    HttpResponseMessage response1 = HttpMessageParser.parseResponse(onInternalRequest(messageId, body).data());
    assertEquals(201, response1.statusCode());
    assertTrue(ProblemDetail.parse(response1.body()).detail().startsWith("Light is on!"));
    HttpResponseMessage response2 = HttpMessageParser.parseResponse(onInternalRequest(messageId, body).data());
    assertEquals(201, response2.statusCode());
    assertEquals(response1.body(), response2.body());
  }

  @Test
  public void whenNonDuplicateRequestWithSameMessageIdThenRespondWithBadRequest() throws JsonProcessingException {
    String messageId = UUID.randomUUID().toString();
    HttpResponseMessage response1 = HttpMessageParser.parseResponse(onInternalRequest(messageId, new InternalProcess("A", 1)).data());
    assertTrue(ProblemDetail.parse(response1.body()).detail().startsWith("Light is on!"));
    HttpResponseMessage response2 = HttpMessageParser.parseResponse(onInternalRequest(messageId, new InternalProcess("B", 2)).data());
    assertEquals(400, response2.statusCode());
    assertEquals("Conflict", ProblemDetail.parse(response2.body()).detail());
  }

  @Test
  public void whenRollbackAfterRequestCompletedThenRespondOk() throws JsonProcessingException {
    String messageId = UUID.randomUUID().toString();
    Event<?> responseEvent = onInternalRequest(messageId, new InternalProcess("Hello!", 100));
    HttpResponseMessage response = HttpMessageParser.parseResponse(responseEvent.data());
    assertTrue(ProblemDetail.parse(response.body()).detail().startsWith("Light is on!"));
    Event<?> rollbackResponseEvent = onRollbackRequest(messageId);
    HttpResponseMessage rollbackResponse = HttpMessageParser.parseResponse(rollbackResponseEvent.data());
    assertEquals(201, rollbackResponse.statusCode());
    assertEquals("Rolled back", ProblemDetail.parse(rollbackResponse.body()).detail());
  }

  @Test
  public void whenRollbackBeforeRequestThenRespondOk() throws JsonProcessingException {
    String messageId = UUID.randomUUID().toString();
    Event<?> rollbackResponseEvent = onRollbackRequest(messageId);
    HttpResponseMessage rollbackResponse = HttpMessageParser.parseResponse(rollbackResponseEvent.data());
    assertEquals(201, rollbackResponse.statusCode());
    assertEquals("Rolled back", ProblemDetail.parse(rollbackResponse.body()).detail());

    Event<?> responseEvent = onInternalRequest(messageId, new InternalProcess("Hello!", 123));
    HttpResponseMessage response = HttpMessageParser.parseResponse(responseEvent.data());
    var detail =  ProblemDetail.parse(response.body()).detail();
    assertEquals(400, response.statusCode());
    assertEquals("Rolled back", ProblemDetail.parse(response.body()).detail());
  }

  @Test
  public void formatModel() throws IOException {
    System.out.println(new PlantUMLFormatter(inboxExchange, inboxExchange.transitions(), false).formatToImage("docs/images"));
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record ProblemDetail(String type, String title, int status, String detail, String entityId) {

    static ProblemDetail parse(String body) throws JsonProcessingException {
      var reader = new ObjectMapper().readerFor(ProblemDetail.class);
      return reader.readValue(body);
    }

  }

  private Event<?> onInternalRequest(String messageId, InternalProcess body) {
    return onRequest(PUT, URI.create("/internal/" + messageId), asJson(body));
  }

  private <T> String asJson(T value) {
    try {
      return new ObjectMapper().writeValueAsString(value);
    } catch (JsonProcessingException e) {
      throw new RuntimeException(e);
    }
  }

  private Event<?> onRequest(Method method, URI uri, String body) {
    return onRequest(
        new EventTrigger<>(
            new EventSpec<>(Request, Function.identity()),
            List.of(newEntityId()),
            inboxExchange,
            true
        ),
        new HttpRequestMessage(method, uri, Map.of(), body)
    );
  }

  private Event<?> onRollbackRequest(String messageId) {
    var responseEvent = onRequest(
        new EventTrigger<>(
            // TODO: Change model to accept Request on Responded?
            new EventSpec<>(Request, Function.identity()),
            List.of(secondaryId(MessageId, _ -> new MessageId("x", messageId), CreateIfNotExists)),
            inboxExchange,
            false
        ),
        new HttpRequestMessage(DELETE, URI.create("/lamps/messages/" + messageId), Map.of())
    );
    assertEquals(RollbackResponse, responseEvent.type());
    return responseEvent;
  }

  private Event<?> onRequest(EventTrigger<HttpRequestMessage, ?, ?> eventTrigger, HttpRequestMessage requestMessage) {
    System.out.println(getClass().getSimpleName() + ": onRequest(...) with " + requestMessage.requestLine());
    return stateMachine.onEvent(UUID.randomUUID().toString(), eventTrigger, requestMessage)
        .doOnNext(e -> System.out.println("Test got response: " + e.type().name()))
        .switchIfEmpty(Mono.defer(() -> Mono.error(new RuntimeException("Test error"))))
        // First event is the triggered event.
        .skip(1)
        // Second event is the response event.
        .next()
        .block(Duration.ofSeconds(5));
  }
}
