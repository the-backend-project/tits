package com.github.thxmasj.backend.identityprovider;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.github.thxmasj.backend.core.DataType;
import com.github.thxmasj.backend.core.StateMachine;
import com.github.thxmasj.backend.core.database.Database;
import com.github.thxmasj.backend.core.database.jdbc.DataSourceBuilder;
import com.github.thxmasj.backend.core.message.http.HttpMessageParser;
import com.github.thxmasj.backend.core.message.http.HttpRequestMessage;
import com.github.thxmasj.backend.core.message.http.HttpRequestMessage.Method;
import com.github.thxmasj.backend.core.message.http.HttpResponseMessage;
import com.github.thxmasj.backend.http.inbox.HttpInbox;
import com.github.thxmasj.backend.http.inbox.HttpInboxListener;
import com.github.thxmasj.backend.identityprovider.Config.BootstrapClient;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import reactor.netty.DisposableServer;

/// An OAuth 2.0 authorization server for the client credentials grant, built with the HTTP inbox and the state
/// machine. Clients are registered by other clients, and the first one is bootstrapped from the configuration.
///
/// Usage:
///
/// - `identity-provider` serves HTTP on `$PORT` until terminated.
/// - `identity-provider migrate` creates the database schema and exits. Run it as a one-off admin process before serving.
/// - `identity-provider generate-key` prints a JWK set with a new signing key, for `$SIGNING_KEYS`.
public final class IdentityProvider {

  private IdentityProvider() {}

  public static void main(String[] args) {
    long t0 = System.nanoTime();
    String command = args.length == 0 ? "serve" : args[0];
    if (command.equals("generate-key")) {
      System.out.println(Keys.generate());
      return;
    }
    Config config = Config.fromEnvironment(System.getenv());
    switch (command) {
      case "serve" -> {
        // Invalid keys fail before connecting to the database
        Keys keys = keys(config.requireServing(), Clock.systemUTC());
        StateMachine stateMachine = stateMachine(config);
        DisposableServer server;
        try {
          if (config.bootstrapClient() != null)
            bootstrap(stateMachine, config.bootstrapClient());
          server = serve(stateMachine, keys, config.port());
        } catch (RuntimeException e) {
          // The workers of the state machine would keep the process running without serving
          e.printStackTrace();
          System.exit(1);
          return;
        }
        System.out.println("Listening on port " + server.port() + " after " + Duration.ofNanos(System.nanoTime() - t0).toMillis() + " ms");
        // A container runtime stops the process with SIGTERM, which runs the shutdown hooks
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
          server.disposeNow(Duration.ofSeconds(10));
          System.out.println("Stopped listening");
          try {
            stateMachine.close();
            System.out.println("Stopped state machine");
          } catch (Exception e) {
            System.err.println("Failed to stop state machine: " + e);
          }
        }));
        server.onDispose().block();
      }
      case "migrate" -> {
        migrate(config);
        System.out.println("Migrated schema " + config.schema());
      }
      default -> {
        System.err.println("Usage: identity-provider [serve|migrate|generate-key]");
        System.exit(2);
      }
    }
  }

  /// Creates the schema if it does not exist, and fails if it exists with a different structure.
  public static void migrate(Config config) {
    if (config.role() == null)
      throw new IllegalArgumentException("Environment variable DATABASE_ROLE is required for migration");
    Database database = Database.named(config.database());
    database.createSchema(new DataSourceBuilder(database, config.databaseConfig()).build(), config.schema(), config.role())
        .timeout(Duration.ofSeconds(30))
        .block();
  }

  public static StateMachine stateMachine(Config config) {
    Database database = Database.named(config.database());
    return new StateMachine(
        null,
        Inbox.transitions(config.tokenLifetime()),
        new DataSourceBuilder(database, config.databaseConfig()).buildPooled(),
        null, // The schema is created by the migrate admin process
        config.schema(),
        null,
        Clock.systemUTC(),
        null
    );
  }

  static Keys keys(Config config, Clock clock) {
    return new Keys(config.signingKeys(), config.issuer(), config.audience(), clock);
  }

  public static DisposableServer serve(StateMachine stateMachine, Keys keys, int port) {
    return new HttpInboxListener(stateMachine).filter(new Edge(keys)).listen(port);
  }

  /// Registers the bootstrap client with the registration scope, unless its client id is taken. A taken client id is
  /// most likely the bootstrap client registered by an earlier start, whose secret is kept.
  public static void bootstrap(StateMachine stateMachine, BootstrapClient client) {
    var body = Map.of("client_id", client.id(), "scope", Clients.RegistrationScope);
    var request = Edge.registration(
        Map.of("Content-Type", "application/json"),
        client.id(),
        Secrets.hash(client.secret()),
        new HttpRequestMessage(Method.POST, URI.create("/clients"), Map.of(), DataType.objectMapper.writeValueAsBytes(body))
    );
    HttpResponseMessage response = stateMachine.onEvent("bootstrap", HttpInbox.TRIGGER, request)
        // First event is the triggered event, second is the response event
        .skip(1)
        .next()
        .map(event -> HttpMessageParser.parseResponse(event.data()))
        .timeout(Duration.ofSeconds(30))
        .block();
    switch (response.statusCode()) {
      case 201 -> System.out.println("Registered bootstrap client " + client.id());
      case 409 -> System.out.println("Bootstrap client " + client.id() + " is already registered. Its secret is not changed.");
      default -> throw new IllegalStateException(
          "Failed to register bootstrap client " + client.id() + ": " + response.statusCode() + " " + new String(response.body(), UTF_8)
      );
    }
  }

}
