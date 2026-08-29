package com.github.thxmasj.statemachine.http.inbox;

import static com.github.thxmasj.statemachine.BuiltinEventTypes.Rollback;
import static com.github.thxmasj.statemachine.EntitySelector.entityId;
import static com.github.thxmasj.statemachine.EntitySelector.entityIdFromSession;
import static com.github.thxmasj.statemachine.EntitySelector.newEntityId;
import static com.github.thxmasj.statemachine.TransitionModelBuilder.WithEvent.onEvent;
import static com.github.thxmasj.statemachine.TransitionModelBuilder.assemble;
import static com.github.thxmasj.statemachine.Tuples.tuple;
import static com.github.thxmasj.statemachine.Validated.invalid;
import static com.github.thxmasj.statemachine.Validated.valid;
import static com.github.thxmasj.statemachine.http.inbox.HttpInbox.CompleteRequest;
import static com.github.thxmasj.statemachine.http.inbox.HttpInbox.EntityModels.RequestDispatching;
import static com.github.thxmasj.statemachine.http.inbox.HttpInbox.TRIGGER;
import static com.github.thxmasj.statemachine.http.inbox.HttpInboxTest.Entities.Lamp;
import static com.github.thxmasj.statemachine.http.inbox.HttpInboxTest.States.Off;
import static com.github.thxmasj.statemachine.http.inbox.HttpInboxTest.States.On;
import static com.github.thxmasj.statemachine.http.inbox.HttpInboxTest.States.Processing;
import static com.github.thxmasj.statemachine.http.inbox.HttpInboxTest.States.Unreachable;
import static com.github.thxmasj.statemachine.http.inbox.HttpRequestRoute.ContentRoute.anyContent;
import static com.github.thxmasj.statemachine.http.inbox.HttpRequestRoute.ContentRoute.newEntity;
import static com.github.thxmasj.statemachine.http.inbox.HttpRequestRoute.ContentRoute.parseEntityId;
import static com.github.thxmasj.statemachine.http.inbox.HttpRequestRoute.ContentRoute.parseMessageId;
import static com.github.thxmasj.statemachine.http.inbox.TransitionModels.requestDispatchingTransitions;
import static com.github.thxmasj.statemachine.message.http.HttpRequestMessage.Method.DELETE;
import static com.github.thxmasj.statemachine.message.http.HttpRequestMessage.Method.POST;
import static com.github.thxmasj.statemachine.message.http.HttpRequestMessage.Method.PUT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.thxmasj.statemachine.BasicEventType;
import com.github.thxmasj.statemachine.BasicEventType.Rollback.Data;
import com.github.thxmasj.statemachine.EntityModel;
import com.github.thxmasj.statemachine.Event;
import com.github.thxmasj.statemachine.EventTrigger;
import com.github.thxmasj.statemachine.EventType;
import com.github.thxmasj.statemachine.IncomingResponseValidator.Result.Status;
import com.github.thxmasj.statemachine.Init;
import com.github.thxmasj.statemachine.OutgoingRequestCreator;
import com.github.thxmasj.statemachine.PlantUMLFormatter;
import com.github.thxmasj.statemachine.State;
import com.github.thxmasj.statemachine.StateMachine;
import com.github.thxmasj.statemachine.TransitionModelBuilder.TransitionContext;
import com.github.thxmasj.statemachine.TransitionModelBuilder.TransitionModel;
import com.github.thxmasj.statemachine.Validated.Valid;
import com.github.thxmasj.statemachine.http.NettyHttpClient;
import com.github.thxmasj.statemachine.http.NettyHttpClientBuilder;
import com.github.thxmasj.statemachine.http.inbox.HttpInbox.RoutedRequest;
import com.github.thxmasj.statemachine.http.outbox.AtLeastOnce;
import com.github.thxmasj.statemachine.http.outbox.AtMostOnce;
import com.github.thxmasj.statemachine.http.outbox.Callback;
import com.github.thxmasj.statemachine.http.outbox.HttpOutbox;
import com.github.thxmasj.statemachine.message.http.HttpMessageParser;
import com.github.thxmasj.statemachine.message.http.HttpRequestMessage;
import com.github.thxmasj.statemachine.message.http.HttpRequestMessage.Method;
import com.github.thxmasj.statemachine.message.http.HttpResponseMessage;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.AbstractMap.SimpleImmutableEntry;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

public class HttpInboxTest {

  private static final long PROCESSING_TIMEOUT = 2000;

  enum States implements State {
    On,
    Off,
    Processing {
      @Override
      public Timeout<?> timeout() {
        return rollbackAfter(Duration.ofMillis(PROCESSING_TIMEOUT));
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

  private static HttpOutbox<?> Process0Outbox;
  private static HttpOutbox<?> Process3Outbox;

  private static Map<State, List<TransitionModel<?, ?>>> lampTransitions() {
    return Map.of(
        On, List.of(
            onEvent(InternalProcessing).to(Off).assembleInput().output(d -> d),
            onEvent(SwitchOff).to(Off).output(),
            onEvent(Cancel).toSelf()
                .assemble(c -> tuple(c.input(), c.eventReference()))
                .trigger(CompleteRequest).with(d -> tuple("Cancelled", d.t2())).on(RequestDispatching).identifiedBy(entityIdFromSession())
                .output(d -> d.t1().t1()),
            onEvent(ZeroProcessing).toSelf()
                .assemble(c -> tuple(c.input(), c.eventReference()))
                .trigger(CompleteRequest).with(d -> tuple("Complete", d.t2())).on(RequestDispatching).identifiedBy(entityIdFromSession())
                .output(d -> d.t1().t1())
        ),
        Off, List.of(
            onEvent(InternalProcessing).to(On)
                .assemble(TransitionContext::eventReference)
                .trigger(Process0Outbox.requestDispatched()).on(Process0Outbox).identifiedBy(newEntityId())
                //.trigger(new ProcessRequest(0)).with(_ -> null).to(DeviceListener).guaranteed()
                .trigger(CompleteRequest).with(d -> tuple("Light is on! " + random.nextLong(), d.t1())).on(RequestDispatching).identifiedBy(entityIdFromSession())
                .output(),
            onEvent(ComplexInternalProcessing).to(Processing)
                .assemble(d -> d)
                .trigger(ComplexInternalProcessingDone).on(Lamp).identifiedBy(entityIdFromSession())
                .output(),
            onEvent(ExternalProcessing).to(Processing)
                .assembleInput()
                .trigger(Process0Outbox.requestDispatched()).on(Process0Outbox).identifiedBy(newEntityId())
                //.trigger(new ProcessRequest(0)).with(_ -> null).to(DeviceListener).guaranteed()
//                .responseValidator((_, _, _, _) -> Mono.just(new Result(
//                    Status.Ok,
//                    "Ok",
//                    new InputEvent<>(ExternalProcessingDone, null)
//                )))
                .output(),
            onEvent(LongExternalProcessing).to(Processing)
                .assembleInput()
                .trigger(Process3Outbox.requestDispatched()).on(Process3Outbox).identifiedBy(newEntityId())
//                .trigger(new ProcessRequest(LONG_EXTERNAL_PROCESSING))
//                .with(_ -> null)
//                .to(DeviceListener)
//                .guaranteed()
//                .responseValidator((_, _, _, _) -> Mono.just(new Result(
//                    Status.Ok,
//                    "Ok",
//                    new InputEvent<>(ExternalProcessingDone, null)
//                )))
                .reversible(
                    assemble((log, rollbackType) -> "")
                        // NB: Response in request/reply session not possible with reversals triggered by the resolver
                        //.trigger(ComplexInternalProcessResponse).with(_ -> "Failed to switch on light! :(((").on(inboxExchange).identifiedBy(entityIdFromSession())
                        .trigger(Process0Outbox.requestDispatched()).on(Process0Outbox).identifiedBy(newEntityId())
                        //.trigger(new ProcessRequest(0)).with(_ -> null).to(DeviceListener).guaranteed()
//                        .responseValidator((_, _, _, _) -> Mono.just(new Result(
//                            Status.Ok,
//                            "Ok",
//                            new InputEvent<>(ExternalProcessingDone, null)
//                        )))
//                        .complete()
                )
                .output(),
            onEvent(SwitchOn).to(On).output()
        ),
        Processing, List.of(
            onEvent(Rollback).toSelf().assembleInput().output(d -> d),
            onEvent(ComplexInternalProcessingDone).to(On)
                .assemble(TransitionContext::eventReference)
                .trigger(CompleteRequest).with(d -> tuple("Phew! Light is switched on! " + random.nextLong(), d)).on(RequestDispatching).identifiedBy(entityIdFromSession())
                .output(),
            onEvent(ExternalProcessingDone).to(On)
                .assemble(TransitionContext::eventReference)
                .trigger(CompleteRequest).with(d -> tuple("Light is externally switched on! " + random.nextLong(), d)).on(RequestDispatching).identifiedBy(entityIdFromSession())
                .output()
        ),
        Unreachable, List.of(
            onEvent(EventThatIsAlwaysRejected).toSelf().output()
        )
    );
  }

  record InternalProcess(String a, int b) {}

  public record Zero(String value) {}

  static EventType<Zero, Zero>
      ZeroProcessing = BasicEventType.of(
      "ZeroProcessing",
      UUID.fromString("aa70041a-a68f-4bcd-831f-5029eb329a05"),
      Zero.class
  );
  static EventType<Void, Void>
      EventThatIsAlwaysRejected = BasicEventType.of(
      "EventThatIsAlwaysRejected",
      UUID.fromString("bd98a725-ac60-4db4-b7db-ce08a295ffca")
  ),
      InternalProcessing = BasicEventType.of(
          "InternalProcessing",
          UUID.fromString("5909336e-cc75-4c58-902f-fb68f08b0caa")
      ),
      ComplexInternalProcessing = BasicEventType.of(
          "ComplexInternalProcessing",
          UUID.fromString("16c1792d-9fb7-4f91-b55b-b59e2b00bad8")
      ),
      ComplexInternalProcessingDone = BasicEventType.of(
          "ComplexInternalProcessingDone",
          UUID.fromString("88c87c85-7778-4171-bc75-702f9e60b00e")
      );
  static EventType<Void, Void> ExternalProcessingDone = BasicEventType.of(
      "ExternalProcessingDone",
      UUID.fromString("464fca06-f872-4e38-a167-5550f5247310"),
      Void.class
  );
  static EventType<BasicEventType.Rollback.Data, BasicEventType.Rollback.Data>
      Cancel = new BasicEventType.Cancel("Cancel", UUID.fromString("d94c29c8-f113-4dee-921f-1a8e58f916f4"));
  static EventType<String, String>
      ExternalProcessing = BasicEventType.of(
      "ExternalProcessing",
      UUID.fromString("12a97e70-58cc-46a8-aed9-f867cfc7375f"),
      String.class
  ),
      LongExternalProcessing = BasicEventType.of(
          "LongExternalProcessing",
          UUID.fromString("6ebcbbec-5bbc-4c05-96fd-41ad0bbc097a"),
          String.class
      );
  static EventType<Void, Void>
      SwitchOn = BasicEventType.of("SwitchOn", UUID.fromString("5e9a8a9d-6a21-41cf-82dc-857fe1e4c4e0")),
      SwitchOff = BasicEventType.of("SwitchOff", UUID.fromString("8e1483c8-b649-43b8-b352-5094a94c0dad"));

  static <K, V> Entry<K, V> entry(K key, V value) {
    return new SimpleImmutableEntry<>(key, value);
  }

  static List<HttpRequestRoute<?>> routes = List.of(
      new HttpRequestRoute<>(
          m -> m.requestLine().matches("PUT .*/zero/.*"),
          m -> valid(m.body()),
          List.of(anyContent(null, null, UUID.fromString("7e2c5175-a65d-4795-a285-b0d75e704f5a"), ZeroProcessing, Lamp, (RoutedRequest<String> _) -> new Zero("Hey!"), parseEntityId("PUT .*/zero/(.*)", 1)))
      ),
      new HttpRequestRoute<>(
          m -> m.requestLine().matches("PUT .*/internal/.*"),
          m -> valid(m.body()),
          List.of(anyContent(parseMessageId("PUT .*/internal/(.*)", 1), null, UUID.fromString("3afa1f14-d4e3-49c4-b6ad-05a733f0b22d"), InternalProcessing, Lamp, (RoutedRequest<String> _) -> null, newEntity()))
      ),
      new HttpRequestRoute<>(
          m -> m.requestLine().matches("PUT .*/handledunknown/.*"),
          m -> valid(m.body()),
          List.of(anyContent(null, null, UUID.fromString("b411fd45-6c87-43aa-a511-df930d654ec7"), InternalProcessing, Lamp, (RoutedRequest<String> _) -> null, (_, _) -> new Valid<>(entityId(UUID.randomUUID())))) // Unknown id
      ),
      new HttpRequestRoute<>(
          m -> m.requestLine().matches("PUT .*/handledreject/.*"),
          m -> valid(m.body()),
          List.of(anyContent(null, null, UUID.fromString("00f910e2-8c22-4403-835d-15e112ac3080"), EventThatIsAlwaysRejected, Lamp, (RoutedRequest<String> _) -> null, newEntity()))
      ),
      new HttpRequestRoute<>(
          m -> m.requestLine().matches("PUT .*/complexinternal/.*"),
          m -> valid(m.body()),
          List.of(anyContent(parseMessageId("PUT .*/complexinternal/(.*)", 1), null, UUID.fromString("84164954-ad85-4b44-9de6-079bf4805df7"), ComplexInternalProcessing, Lamp, (RoutedRequest<String> _) -> null, newEntity()))
      ),
      new HttpRequestRoute<>(
          m -> m.requestLine().matches("PUT .*/external/.*"),
          m -> valid(m.body()),
          List.of(anyContent(parseMessageId("PUT .*/external/(.*)", 1), null, UUID.fromString("415cfd95-6feb-4106-8d47-fcf41ddcb3d1"), ExternalProcessing, Lamp, (RoutedRequest<String> _) -> null, newEntity()))
      ),
      new HttpRequestRoute<>(
          m -> m.requestLine().matches("PUT .*/long-external/.*"),
          m -> valid(m.body()),
          List.of(anyContent(null, null, UUID.fromString("721fedb2-e715-4c4d-a8fb-e2cdb16f86e5"), LongExternalProcessing, Lamp, (RoutedRequest<String> _) -> null, newEntity()))
      ),
      new HttpRequestRoute<>(
          m -> m.requestLine().matches("PUT .*/rejected/.*"),
          m -> valid(m.body()),
          List.of(anyContent(parseMessageId("PUT .*/rejected/(.*)", 1), null, UUID.fromString("e58f3910-0b01-4f8b-bbf3-c7fde4980197"), EventThatIsAlwaysRejected, Lamp, (RoutedRequest<String> _) -> null, newEntity()))
      ),
      new HttpRequestRoute<>(
          m -> m.requestLine().matches("DELETE .*/internal/.*"),
          _ -> valid(null),
          List.of(anyContent(
              null,
              null,
              UUID.fromString("f5ef4720-c6bc-4686-be8b-85eeb0fdc372"),
              Rollback,
              Lamp,
              _ -> new Data(0, Integer.MAX_VALUE, "Cancel"),
              parseEntityId("DELETE .*/internal/(.*)", 1)
          ))
      ),
      new HttpRequestRoute<>(
          m -> m.requestLine().matches("DELETE .*/lamps/messages/.*"),
          _ -> valid(null),
          List.of(anyContent(
              parseMessageId("DELETE .*/lamps/messages/(.*)", 1),
              null,
              UUID.fromString("56829b3a-78ce-40e4-9006-a6c4d3b6dc23"),
              Rollback,
              Lamp,
              _ -> null,
              null
          ))
      )
  );

  private static HttpRequestMessage requestMessage(String path) {
    return new HttpRequestMessage(
        POST,
        URI.create("http://localhost:" + server.getAddress().getPort() + path)
    );
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

  static Status status(HttpResponseMessage responseMessage) {
    int c = responseMessage.statusCode();
    if (c >= 200 && c < 300) {
      return Status.Ok;
    } else if (c >= 400 && c < 500) {
      return Status.PermanentError;
    } else if (c >= 500 && c < 600) {
      return Status.TransientError;
    } else {
      return Status.PermanentError;
    }
  }


  @BeforeAll
  public static void setUp() throws IOException {
    server = Init.httpServer();
    Init.addDelayContext(server, "/long-process", Duration.ofSeconds(10));
    Init.addDelayContext(server, "/process", uri -> Duration.ofMillis(Long.parseLong(uri.getPath().substring("/process/".length()))));
    Process0Outbox = new AtMostOnce<>(
        "Process0Outbox",
        UUID.fromString("2c68ffa2-0935-46fd-a032-2647ca51b801"),
        Void.class,
        _ -> requestMessage("/process/0"),
        new NettyHttpClient(new NettyHttpClientBuilder().build()),
        Lamp,
        new Callback<>(ExternalProcessingDone, _ -> null), // onPeerUnavailable
        new Callback<>(ExternalProcessingDone, _ -> null), // onMissingResponse
        new Callback<>(ExternalProcessingDone, _ -> null), // onSuccess
        new Callback<>(ExternalProcessingDone, _ -> null), // onFailure
        new Callback<>(ExternalProcessingDone, _ -> null), // onInvalidResponseRejection
        new Callback<>(ExternalProcessingDone, _ -> null), // onInvalidResponseUnknown
        message -> message.body() == null ? valid(null) : invalid("Expected empty body"), // contentParser
        r -> r.t1().statusCode() >= 200 && r.t1().statusCode() <= 299, // successPredicate
        r -> r.t1().statusCode() >= 400 && r.t1().statusCode() <= 499, // invalidResponseRejectionPredicate
        new AtLeastOnce<>(
            "Process0RollbackOutbox",
            UUID.fromString("513f7e3e-c03e-4016-b5f3-ddb981cb1ffe"),
            Void.class,
            _ -> requestMessage("/rollback"),
            (_, o) -> o,
            new NettyHttpClient(new NettyHttpClientBuilder().build()),
            null, // processModel
            null, // onSuccess
            null, // onFailure
            _ -> null, // contentParser
            r -> r.message().statusCode() >= 200 && r.message().statusCode() <= 299,
            r -> r.message().statusCode() >= 500 && r.message().statusCode() <= 599, // transientFailurePredicate
            r -> r.message().statusCode() >= 400 && r.message().statusCode() <= 499 // permanentFailurePredicate
        )
    );
    Process3Outbox = new AtLeastOnce<>(
        "Process3Outbox",
        UUID.fromString("2e86f07c-50ed-4922-9839-54dca94be4b6"),
        Void.class,
        _ -> requestMessage("/process/3000"),
        (_, requestMessage) -> requestMessage,
        new NettyHttpClient(new NettyHttpClientBuilder().build()),
        Lamp,
        new Callback<>(ExternalProcessingDone, _ -> null),
        new Callback<>(ExternalProcessingDone, _ -> null),
        _ -> null, // contentParser
        r -> r.message().statusCode() >= 200 && r.message().statusCode() <= 299,
        r -> r.message().statusCode() >= 500 && r.message().statusCode() <= 599, // transientFailurePredicate
        r -> r.message().statusCode() >= 400 && r.message().statusCode() <= 499 // permanentFailurePredicate
    );
    stateMachine = Init.stateMachine(
        Lamp,
        lampTransitions(),
        routes,
        List.of(Process0Outbox, Process3Outbox)
    );
  }

  @Test
  public void whenInternalProcessRequestThenInternalProcessResponse() throws JsonProcessingException {
    String messageId = UUID.randomUUID().toString();
    Event<?> responseEvent = onRequest(
        PUT,
        URI.create("/internal/" + messageId),
        new ObjectMapper().writeValueAsString(new InternalProcess("Hello", 1))
    );
    HttpResponseMessage response = HttpMessageParser.parseResponse(responseEvent.data());
    assertEquals(201, response.statusCode());
    assertTrue(ProblemDetail.parse(response.body()).detail().startsWith("Light is on!"));
  }

  @Test
  public void whenZeroingInternalProcessRequestThenAcceptedResponse() throws JsonProcessingException {
    String messageId = UUID.randomUUID().toString();
    Event<?> responseEvent = onRequest(
        PUT,
        URI.create("/internal/" + messageId),
        new ObjectMapper().writeValueAsString(new InternalProcess("Hello", 1))
    );
    HttpResponseMessage response = HttpMessageParser.parseResponse(responseEvent.data());
    assertEquals(201, response.statusCode());
    ProblemDetail problemDetail = ProblemDetail.parse(response.body());
    assertTrue(problemDetail.detail().startsWith("Light is on!"));
    UUID entityId = UUID.fromString(problemDetail.entityId());

    Event<?> zeroResponseEvent = onRequest(PUT, URI.create("/zero/" + entityId), null);
    HttpResponseMessage zeroResponse = HttpMessageParser.parseResponse(zeroResponseEvent.data());
    assertEquals(201, zeroResponse.statusCode());
    assertEquals("Complete", ProblemDetail.parse(zeroResponse.body()).detail());
  }

  @Test
  public void whenCancelInternalProcessRequestThen() throws JsonProcessingException {
    String messageId = UUID.randomUUID().toString();
    Event<?> responseEvent = onRequest(
        PUT,
        URI.create("/internal/" + messageId),
        new ObjectMapper().writeValueAsString(new InternalProcess("Hello", 1))
    );
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
    assertThat(pd.detail()).matches("\\[EventThatIsAlwaysRejected] on \\[Lamp]/.{36} rejected for state \\[Off]");
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
  public void whenUnknownIdThenBadRequest() throws JsonProcessingException {
    String messageId = UUID.randomUUID().toString();
    Event<?> responseEvent = onRequest(PUT, URI.create("/handledunknown/" + messageId), "Hello!");
    HttpResponseMessage response = HttpMessageParser.parseResponse(responseEvent.data());
    assertEquals(400, response.statusCode());
    assertEquals("No such entity", ProblemDetail.parse(response.body()).detail());
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
    HttpResponseMessage response1 = HttpMessageParser.parseResponse(onInternalRequest(
        messageId,
        new InternalProcess("A", 1)
    ).data());
    assertTrue(ProblemDetail.parse(response1.body()).detail().startsWith("Light is on!"));
    HttpResponseMessage response2 = HttpMessageParser.parseResponse(onInternalRequest(
        messageId,
        new InternalProcess("B", 2)
    ).data());
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
    assertEquals("Nothing to roll back", ProblemDetail.parse(rollbackResponse.body()).detail());

    // Verify that a request coming in afterward is rejected (422)
    Event<?> responseEvent = onInternalRequest(messageId, new InternalProcess("Hello!", 123));
    HttpResponseMessage response = HttpMessageParser.parseResponse(responseEvent.data());
    var detail = ProblemDetail.parse(response.body()).detail();
    assertEquals(422, response.statusCode());
    assertEquals("Rolled back", ProblemDetail.parse(response.body()).detail());
  }

  @Test
  public void formatModel() throws IOException {
    System.out.println(new PlantUMLFormatter(RequestDispatching, requestDispatchingTransitions(routes, List.of()), false).formatToImage(
        "docs/images"));
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
    return onRequest(TRIGGER, new HttpRequestMessage(method, uri, Map.of(), body));
  }

  private Event<?> onRollbackRequest(String messageId) {
    return onRequest(DELETE, URI.create("/lamps/messages/" + messageId), null);
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
