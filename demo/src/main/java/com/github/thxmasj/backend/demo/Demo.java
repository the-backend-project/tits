package com.github.thxmasj.backend.demo;

import com.github.thxmasj.backend.core.StateMachine;
import com.github.thxmasj.backend.core.database.jdbc.DataSourceBuilder;
import com.github.thxmasj.backend.core.database.jdbc.JDBCClient;
import com.github.thxmasj.backend.core.database.mssql.CreateSchema;
import com.github.thxmasj.backend.http.inbox.HttpInboxListener;
import com.github.thxmasj.backend.core.message.http.HttpRequestMessage.Method;
import com.github.thxmasj.backend.core.message.http.HttpResponseMessage;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;

/// A hello world web application built with the HTTP inbox and the state machine, following the principles of
/// [the twelve-factor app](https://12factor.net/).
///
/// Usage:
///
/// - `demo` serves HTTP on `$PORT` until terminated.
/// - `demo migrate` creates the database schema and exits. Run it as a one-off admin process before serving.
public final class Demo {

  private Demo() {}

  public static void main(String[] args) {
    long t0 = System.nanoTime();
    Config config = Config.fromEnvironment(System.getenv());
    switch (args.length == 0 ? "serve" : args[0]) {
      case "serve" -> {
        StateMachine stateMachine = stateMachine(config);
        DisposableServer server = serve(stateMachine, config.port());
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
        System.err.println("Usage: demo [serve|migrate]");
        System.exit(2);
      }
    }
  }

  /// Creates the schema if it does not exist, and fails if it exists with a different structure.
  public static void migrate(Config config) {
    if (config.role() == null)
      throw new IllegalArgumentException("Environment variable DATABASE_ROLE is required for migration");
    new CreateSchema(
        HelloWorld.transitions().keySet().stream().flatMap(m -> m.secondaryIds().stream()).distinct().toList(),
        config.schema(),
        config.role()
    ).execute(new JDBCClient(new DataSourceBuilder(config.database()).build()))
        .timeout(Duration.ofSeconds(30))
        .block();
  }

  public static StateMachine stateMachine(Config config) {
    return new StateMachine(
        null,
        HelloWorld.transitions(),
        new DataSourceBuilder(config.database()).buildPooled(),
        null, // The schema is created by the migrate admin process
        config.schema(),
        null,
        Clock.systemUTC(),
        new StdoutListener()
    );
  }

  public static DisposableServer serve(StateMachine stateMachine, int port) {
    return new HttpInboxListener(stateMachine)
        .filter((request, next) -> isHealthCheck(request.method(), request.uri().getPath())
            ? Mono.just(new HttpResponseMessage(200, "OK", Map.of("Content-Type", "text/plain"), "OK".getBytes(StandardCharsets.UTF_8)))
            : next.apply(request))
        .listen(port);
  }

  private static boolean isHealthCheck(Method method, String path) {
    return method == Method.GET && path.equals("/health");
  }

}
