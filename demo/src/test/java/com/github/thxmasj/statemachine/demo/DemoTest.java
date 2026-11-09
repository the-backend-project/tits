package com.github.thxmasj.statemachine.demo;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.thxmasj.statemachine.runtime.StateMachine;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import reactor.netty.DisposableServer;

/// Runs the demo against the test database, the way it is deployed: migrate as admin, then serve as an ordinary login.
public class DemoTest {

  private static final String schema = UUID.randomUUID().toString();
  private static StateMachine stateMachine;
  private static DisposableServer server;

  @BeforeAll
  public static void setUp() {
    Demo.migrate(config("sa", "A_Str0ng_Required_Password"));
    Config config = config("testlogin", "Please_hide_me!");
    stateMachine = Demo.stateMachine(config);
    server = Demo.serve(stateMachine, config.port());
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
  public void whenGetUnknownPathThenBadRequest() throws IOException, InterruptedException {
    assertThat(get("/unknown", Map.of()).statusCode()).isEqualTo(400);
  }

  @Test
  public void whenGetHealthThenOk() throws IOException, InterruptedException {
    assertThat(get("/health", Map.of()).statusCode()).isEqualTo(200);
  }

  private static HttpResponse<String> get(String path, Map<String, String> headers) throws IOException, InterruptedException {
    var request = HttpRequest.newBuilder(URI.create("http://localhost:" + server.port() + path));
    headers.forEach(request::header);
    try (var client = HttpClient.newHttpClient()) {
      return client.send(request.build(), BodyHandlers.ofString());
    }
  }

}
