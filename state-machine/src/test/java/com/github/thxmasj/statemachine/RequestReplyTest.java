package com.github.thxmasj.statemachine;

import static com.github.thxmasj.statemachine.BuiltinEntities.InboxExchange.MessageId;
import static com.github.thxmasj.statemachine.BuiltinEntities.InboxExchange.Request;
import static com.github.thxmasj.statemachine.BuiltinEntities.InboxExchange.Response;
import static com.github.thxmasj.statemachine.BuiltinEntities.InboxExchange.RollbackResponse;
import static com.github.thxmasj.statemachine.BuiltinEntities.InboxExchange.State.Dispatched;
import static com.github.thxmasj.statemachine.EntitySelector.CreationMode.CreateIfNotExists;
import static com.github.thxmasj.statemachine.EntitySelector.entityId;
import static com.github.thxmasj.statemachine.EntitySelector.entityIdFromSession;
import static com.github.thxmasj.statemachine.EntitySelector.newEntityId;
import static com.github.thxmasj.statemachine.EntitySelector.secondaryId;
import static com.github.thxmasj.statemachine.RequestReplyTest.Queues.DeviceListener;
import static com.github.thxmasj.statemachine.RequestReplyTest.States.Off;
import static com.github.thxmasj.statemachine.RequestReplyTest.States.On;
import static com.github.thxmasj.statemachine.TransitionModelBuilder.WithEvent.onEvent;
import static com.github.thxmasj.statemachine.Tuples.tuple;
import static com.github.thxmasj.statemachine.message.http.HttpRequestMessage.Method.DELETE;
import static com.github.thxmasj.statemachine.message.http.HttpRequestMessage.Method.POST;
import static com.github.thxmasj.statemachine.message.http.HttpRequestMessage.Method.PUT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.thxmasj.statemachine.BuiltinEntities.InboxExchange;
import com.github.thxmasj.statemachine.BuiltinEntities.InboxExchange.RequestType;
import com.github.thxmasj.statemachine.EventTrigger.EventSpec;
import com.github.thxmasj.statemachine.TransitionModelBuilder.TransitionModel;
import com.github.thxmasj.statemachine.Tuples.Tuple2;
import com.github.thxmasj.statemachine.database.Client.Config;
import com.github.thxmasj.statemachine.database.jdbc.DataSourceBuilder;
import com.github.thxmasj.statemachine.http.NettyHttpClient;
import com.github.thxmasj.statemachine.http.NettyHttpClientBuilder;
import com.github.thxmasj.statemachine.message.http.Created;
import com.github.thxmasj.statemachine.message.http.HttpMessageParser;
import com.github.thxmasj.statemachine.message.http.HttpRequestMessage;
import com.github.thxmasj.statemachine.message.http.HttpResponseMessage;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

public class RequestReplyTest {

  private static final DataSource dataSource = new DataSourceBuilder(databaseConfig("testlogin", "Please_hide_me!")).build();
  private static final DataSource migrationDataSource = new DataSourceBuilder(databaseConfig("sa", "A_Str0ng_Required_Password")).build();

  private static Config databaseConfig(String username, String password) {
    return new Config(
        "localhost",
        11433,
        "work",
        username,
        password,
        true,
        10,
        Duration.ofSeconds(10)
    );
  }

  enum States implements State {On, Off}

  private static final Random random = new Random();

  private static class Lamp implements EntityModel {

    @Override
    public String name() {
      return "Lamp";
    }

    @Override
    public UUID id() {
      return UUID.fromString("59d3158c-7b2a-4312-a293-325858c2673f");
    }

    @Override
    public State initialState() {
      return Off;
    }

  }

  private static final EntityModel lamp = new Lamp();

  private static Map<State, List<TransitionModel<?, ?>>> lampTransitions() {
    return Map.of(
        On, List.of(
            onEvent(Toggle).to(Off).assembleInput().output(d -> d),
            onEvent(SwitchOff).to(Off).output()
        ),
        Off, List.of(
            onEvent(Toggle).to(On)
                .assembleInput()
                .trigger(new LampRequest()).with(_ -> null).to(DeviceListener).guaranteed()
                .trigger(ToggleResponse).with(_ -> "Light is on! " + random.nextLong()).on(inboxExchange).identifiedBy(entityIdFromSession())
                .output(Tuple2::t1),
            onEvent(SwitchOn).to(On).output()
        )
    );
  }


  static RequestType ToggleRequest = new RequestType("ToggleRequest", UUID.fromString("5085d340-1856-43fc-914b-810654efd12b"));
  static RequestType OneWayRequest = new RequestType("OneWayRequest", UUID.fromString("a0c01f4b-31b0-444a-b77b-03e6baf5c967"));
  static EventType<String, Void> ToggleResponse = BasicEventType.of("ToggleResponse", UUID.fromString("b75ca624-010e-43ab-ae38-19f62289f4ea"), String.class, Void.class);

  static EventType<Void, Void>
      Toggle = BasicEventType.of("Toggle", UUID.fromString("46b0211e-f583-49b3-a6e7-8d13742e0260"), Void.class);
  static EventType<Void, Void>
      SwitchOn = BasicEventType.of("SwitchOn", UUID.fromString("5e9a8a9d-6a21-41cf-82dc-857fe1e4c4e0")),
      SwitchOff = BasicEventType.of("SwitchOff", UUID.fromString("8e1483c8-b649-43b8-b352-5094a94c0dad"))
          ;

  static InboxExchange inboxExchange = new InboxExchange() {

    @Override
    protected List<TransitionModel<?, ?>> requestTransitions() {
      return List.of(
          onEvent(Request).to(Dispatched)
              .assembleInput()
              .when(m -> m.requestLine().matches("PUT .*/lamps/.*"))
              .then(onEvent(ToggleRequest).to(Dispatched)
                  .assemble((input, log) -> tuple(input.data(), log.entityId()))
                  .newIdentifier(
                      MessageId,
                      d -> new MessageId("x", from(d.t1().requestLine(), "PUT .*/lamps/(.*)", 1))
                  )
                  .trigger(Toggle).on(lamp).identifiedBy(newEntityId())
                  .newIdentifier(
                      EventReference,
                      d -> new EventReference(d.t2().entityId(), d.t2().event().eventNumber())
                  )
                  .output(d -> d.t1().t1())
              )
              .when(m -> m.requestLine().matches("PUT .*/oneway/.*"))
              .then(onEvent(OneWayRequest).to(Dispatched)
                  .assemble((input, log) -> tuple(input.data(), log.entityId()))
                  .output(d -> d.t1())
              )
              // RollbackRequest arrives before ToggleRequest. Create message id to block ToggleRequest if it comes
              .when(m -> m.requestLine().matches("DELETE .*/lamps/messages/.*"))
              .then(initialRollbackRequest(d -> new MessageId(
                  "x",
                  from(d.requestLine(), "DELETE .*/lamps/messages/(.*)", 1)
              )))
              .when(_ -> true).then(invalidRequest(), m -> "Request not mapped: " + m.requestLine())
              .output(d -> d)
      );
    }

    @Override
    protected List<TransitionModel<?, ?>> responseTransitions() {
      return List.of(onResponseEvent(ToggleResponse, new Created()));
    }

    @Override
    protected Predicate<HttpRequestMessage> rollbackPredicate() {
      return m -> m.requestLine().matches("DELETE .*/lamps/messages/.*");
    }

    @Override
    protected EntityModel rollbackEntity() {
      return lamp;
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

  static class LampRequest implements OutgoingRequestCreator<Void> {

    @Override
    public HttpRequestMessage create(Void data, Context context) {
      return new HttpRequestMessage(
          POST,
          URI.create("http://localhost:" + server.getAddress().getPort() + "/lamps/" + context.entityId().value())
      );
    }

    @Override
    public UUID id() {
      return UUID.fromString("e0eadb64-224a-480c-b1a2-aab29927fb7e");
    }
  }

  private static StateMachine stateMachine;
  private static HttpServer server;

  @BeforeAll
  public static void setUp() throws IOException {
    server = HttpServer.create(new InetSocketAddress(0), 1);
    Map<EntityModel, Map<State, List<TransitionModel<?, ?>>>> transitions = new HashMap<>();
    transitions.put(lamp, lampTransitions());
    transitions.put(inboxExchange, inboxExchange.transitions());
    stateMachine = new StateMachine(
        null, // request mapper is now an entity too (Inbox)
//        message -> switch (message.requestLine()) {
//          case String l when l.matches("PUT .*/lamps/.*") -> validator(new IncomingRequestValidator<Void>() {})
//              .trigger(event(Toggle)
//                  .onEntity(Lamp)
//                  .identifiedBy(entityId(fromRequestLine(message, "PUT .*/lamps/(.*)", 1), CreationMode.CreateIfNotExists))
//              )
//              .clientId("system")
//              .derivedMessageId();
//          default -> throw new IllegalStateException("Unexpected value: " + message);
//        },
        _ -> Mono.empty(),
        new BeanRegistry() {
          @Override
          public <T> T getBean(Class<T> type) {
            return null;
          }
        },
        transitions,
        dataSource,
        migrationDataSource,
        UUID.randomUUID().toString(),
        "Test",
        Clock.systemUTC(),
        new Logger("RequestReplyTest"),
        queue -> switch (queue) {
          case DeviceListener -> new NettyHttpClient(new NettyHttpClientBuilder().build());
          default -> null;
        }
    );
  }

  @Test
  public void whenRequestThenResponse() throws JsonProcessingException {
    String messageId = UUID.randomUUID().toString();
    Event<?> responseEvent = onToggleRequest(messageId, "Hello!");
    HttpResponseMessage response = HttpMessageParser.parseResponse(responseEvent.data());
    assertEquals(201, response.statusCode());
    assertTrue(ResponseBody.parse(response.body()).detail().startsWith("Light is on!"));
  }

  @Test
  public void whenOneWayRequestThen() throws JsonProcessingException {
    Event<?> responseEvent = onPutRequest(URI.create("/oneway/xxx"), "Hello!");
    HttpResponseMessage response = HttpMessageParser.parseResponse(responseEvent.data());
    assertEquals(201, response.statusCode());
    assertTrue(ResponseBody.parse(response.body()).detail().startsWith("Light is on!"));
  }

  @Test
  public void whenDuplicateRequestThenRespondWithSameResponse() throws JsonProcessingException {
    String messageId = UUID.randomUUID().toString();
    String body = "Static";
    HttpResponseMessage response1 = HttpMessageParser.parseResponse(onToggleRequest(messageId, body).data());
    assertEquals(201, response1.statusCode());
    assertTrue(ResponseBody.parse(response1.body()).detail().startsWith("Light is on!"));
    HttpResponseMessage response2 = HttpMessageParser.parseResponse(onToggleRequest(messageId, body).data());
    assertEquals(201, response2.statusCode());
    assertEquals(response1.body(), response2.body());
  }

  @Test
  public void whenNonDuplicateRequestWithSameMessageIdThenRespondWithBadRequest() throws JsonProcessingException {
    String messageId = UUID.randomUUID().toString();
    HttpResponseMessage response1 = HttpMessageParser.parseResponse(onToggleRequest(messageId, "Body A").data());
    assertTrue(ResponseBody.parse(response1.body()).detail().startsWith("Light is on!"));
    HttpResponseMessage response2 = HttpMessageParser.parseResponse(onToggleRequest(messageId, "Body B").data());
    assertEquals(400, response2.statusCode());
    assertEquals("Conflict", ResponseBody.parse(response2.body()).detail());
  }

  @Test
  public void whenRollbackAfterRequestCompletedThenRespondOk() throws JsonProcessingException {
    String messageId = UUID.randomUUID().toString();
    Event<?> responseEvent = onToggleRequest(messageId, "Hello!");
    HttpResponseMessage response = HttpMessageParser.parseResponse(responseEvent.data());
    assertTrue(ResponseBody.parse(response.body()).detail().startsWith("Light is on!"));
    Event<?> rollbackResponseEvent = onRollbackRequest(messageId);
    HttpResponseMessage rollbackResponse = HttpMessageParser.parseResponse(rollbackResponseEvent.data());
    assertEquals(201, rollbackResponse.statusCode());
    assertEquals("Rolled back", ResponseBody.parse(rollbackResponse.body()).detail());
  }

  @Test
  public void whenRollbackBeforeRequestThenRespondOk() throws JsonProcessingException {
    String messageId = UUID.randomUUID().toString();
    Event<?> rollbackResponseEvent = onRollbackRequest(messageId);
    HttpResponseMessage rollbackResponse = HttpMessageParser.parseResponse(rollbackResponseEvent.data());
    assertEquals(201, rollbackResponse.statusCode());
    assertEquals("Rolled back", ResponseBody.parse(rollbackResponse.body()).detail());

    Event<?> responseEvent = onToggleRequest(messageId, "Hello!");
    HttpResponseMessage response = HttpMessageParser.parseResponse(responseEvent.data());
    assertEquals(400, response.statusCode());
    assertEquals("Rolled back", ResponseBody.parse(response.body()).detail());

  }

  @Test
  public void formatModel() throws IOException {
    System.out.println(new PlantUMLFormatter(inboxExchange, inboxExchange.transitions()).formatToImage("docs/images"));
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record ResponseBody(String type, String title, int status, String detail, String entityId) {

    static ResponseBody parse(String body) throws JsonProcessingException {
      var reader = new ObjectMapper().readerFor(ResponseBody.class);
      return reader.readValue(body);
    }

  }

  private Event<?> onToggleRequest(String messageId, String body) {
    return onPutRequest(URI.create("/lamps/" + messageId), body);
  }

  private Event<?> onPutRequest(URI uri, String body) {
    var responseEvent = onRequest(
        new EventTrigger<>(
            new EventSpec<>(Request, Function.identity()),
            List.of(newEntityId()),
            inboxExchange,
            true
        ),
        new HttpRequestMessage(PUT, uri, Map.of(), body)
    );
    assertEquals(Response, responseEvent.type());
    return responseEvent;
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
    return stateMachine.onEvent(UUID.randomUUID().toString(), eventTrigger, requestMessage)
        .doOnNext(e -> System.out.println("Test got response: " + e.type().name()))
        .switchIfEmpty(Mono.defer(() -> Mono.error(new RuntimeException("Test error"))))
        .block(Duration.ofSeconds(5));
  }

}
