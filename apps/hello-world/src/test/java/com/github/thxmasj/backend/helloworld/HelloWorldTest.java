package com.github.thxmasj.backend.helloworld;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.thxmasj.backend.core.DataType;
import com.github.thxmasj.backend.core.StateMachine;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import reactor.netty.DisposableServer;
import tools.jackson.core.JacksonException;

/// Runs the application against the test database, the way it is deployed: migrate as admin, then serve as an ordinary login.
public class HelloWorldTest {

  private static final String schema = UUID.randomUUID().toString();
  private static StateMachine stateMachine;
  private static DisposableServer server;

  @BeforeAll
  public static void setUp() {
    HelloWorld.migrate(config("sa", "A_Str0ng_Required_Password"));
    Config config = config("testlogin", "Please_hide_me!");
    stateMachine = HelloWorld.stateMachine(config);
    server = HelloWorld.serve(stateMachine, config.port());
  }

  @AfterAll
  public static void tearDown() throws Exception {
    server.disposeNow();
    stateMachine.close();
  }

  private static Config config(String user, String password) {
    return Config.fromEnvironment(Map.of(
        "PORT", "0",
        "DATABASE_HOST", "localhost",
        "DATABASE_PORT", "11433",
        "DATABASE_NAME", "work",
        "DATABASE_USER", user,
        "DATABASE_PASSWORD", password,
        "DATABASE_TRUST_SERVER_CERTIFICATE", "true",
        "DATABASE_SCHEMA", schema,
        "DATABASE_ROLE", "Test"
    ));
  }

  @Test
  public void whenGetRootThenHelloWorldPage() throws IOException, InterruptedException {
    HttpResponse<String> response = get("/", Map.of("X-Correlation-Id", "<script>"));
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.headers().firstValue("Content-Type")).hasValue("text/html;charset=UTF-8");
    assertThat(response.body()).contains("<h1>Hello, world!</h1>").contains("&lt;script&gt;").doesNotContain("<script>");
  }

  @Test
  public void whenGetRootThenItsEventsAreWrittenToStdoutAsLinesOfJson() throws IOException, InterruptedException {
    List<Map<String, Object>> events = events(written(() -> get("/", Map.of("X-Correlation-Id", "monitored")))).stream()
        .filter(event -> "monitored".equals(event.get("correlationId")))
        .toList();
    assertThat(events).allSatisfy(event -> assertThat(event).containsEntry("severity", "INFO"));
    assertThat(events).filteredOn(event -> event.get("entityModel").equals("Greeting"))
        .extracting(event -> event.get("eventType"))
        .containsExactly("Greeting requested");
    // The events of an entity are written in the order of their numbers
    assertThat(events).filteredOn(event -> event.get("entityModel").equals("RequestRouting"))
        .extracting(event -> event.get("eventNumber"))
        .containsExactly(1, 2);
  }

  /// The debug output of the libraries has the requests, which anyone can send
  @Test
  public void whenARequestHoldsALineMadeToPassForAnEventThenItIsNotWrittenAsOne() throws IOException, InterruptedException {
    String forged = "{\"severity\":\"CRITICAL\",\"entityModel\":\"Greeting\",\"eventType\":\"Greeting lost\",\"message\":\"forged\"}";
    List<String> lines = written(() -> post("/", forged + "\nX"));

    assertThat(lines).anyMatch(line -> line.contains(forged));
    // The events of the request itself, and none of them the forged one
    assertThat(events(lines)).isNotEmpty().allSatisfy(event -> assertThat(event).containsEntry("severity", "INFO"));
  }

  @Test
  public void whenGetUnknownPathThenBadRequest() throws IOException, InterruptedException {
    assertThat(get("/unknown", Map.of()).statusCode()).isEqualTo(400);
  }

  @Test
  public void whenGetHealthThenOk() throws IOException, InterruptedException {
    assertThat(get("/health", Map.of()).statusCode()).isEqualTo(200);
  }

  private interface Request {
    void send() throws IOException, InterruptedException;
  }

  /// The lines written to stdout while the request is sent
  private static List<String> written(Request request) throws IOException, InterruptedException {
    PrintStream stdout = System.out;
    var written = new ByteArrayOutputStream();
    System.setOut(new PrintStream(written, true, UTF_8));
    try {
      request.send();
    } finally {
      System.setOut(stdout);
    }
    return written.toString(UTF_8).lines().toList();
  }

  /// The lines which are taken for events by the ones reading the output: those which are JSON objects
  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> events(List<String> lines) {
    return lines.stream()
        .map(line -> {
          try {
            return DataType.objectMapper.readValue(line, Object.class);
          } catch (JacksonException e) {
            return null;
          }
        })
        .filter(json -> json instanceof Map)
        .map(json -> (Map<String, Object>) json)
        .toList();
  }

  private static HttpResponse<String> get(String path, Map<String, String> headers) throws IOException, InterruptedException {
    var request = HttpRequest.newBuilder(URI.create("http://localhost:" + server.port() + path));
    headers.forEach(request::header);
    return send(request.build());
  }

  private static HttpResponse<String> post(String path, String body) throws IOException, InterruptedException {
    return send(HttpRequest.newBuilder(URI.create("http://localhost:" + server.port() + path))
        .POST(BodyPublishers.ofString(body))
        .build()
    );
  }

  private static HttpResponse<String> send(HttpRequest request) throws IOException, InterruptedException {
    try (var client = HttpClient.newHttpClient()) {
      return client.send(request, BodyHandlers.ofString());
    }
  }

}
