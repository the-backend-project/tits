package com.github.thxmasj.backend.helloworld;

import com.github.thxmasj.backend.backoffice.Backoffice;
import com.github.thxmasj.backend.core.JsonLinesListener;
import com.github.thxmasj.backend.core.StateMachine;
import com.github.thxmasj.backend.core.database.Database;
import com.github.thxmasj.backend.core.database.jdbc.DataSourceBuilder;
import com.github.thxmasj.backend.database.mssql.MssqlDatabase;
import com.github.thxmasj.backend.http.inbox.HttpInboxListener;
import com.github.thxmasj.backend.core.message.http.HttpRequestMessage.Method;
import com.github.thxmasj.backend.core.message.http.HttpResponseMessage;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import javax.sql.DataSource;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;

/// A hello world web application built with the HTTP inbox and the state machine, following the principles of
/// [the twelve-factor app](https://12factor.net/).
///
/// Usage:
///
/// - `hello-world` serves HTTP on `$PORT` until terminated.
/// - `hello-world migrate` creates the database schema and exits. Run it as a one-off admin process before serving.
public final class HelloWorld {

  // The application is configured and deployed for SQL Server, including Azure SQL
  private static final Database database = new MssqlDatabase();

  private HelloWorld() {}

  public static void main(String[] args) {
    long t0 = System.nanoTime();
    Config config = Config.fromEnvironment(System.getenv());
    switch (args.length == 0 ? "serve" : args[0]) {
      case "serve" -> {
        DataSource dataSource = dataSource(config);
        StateMachine stateMachine = stateMachine(config, dataSource);
        var backoffice = new Backoffice(config.backoffice(), dataSource, config.schema(), Greetings.transitions());
        DisposableServer server = serve(stateMachine, config.port(), backoffice);
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
        System.err.println("Usage: hello-world [serve|migrate]");
        System.exit(2);
      }
    }
  }

  /// Creates the schema if it does not exist, and fails if it exists with a different structure.
  public static void migrate(Config config) {
    if (config.role() == null)
      throw new IllegalArgumentException("Environment variable DATABASE_ROLE is required for migration");
    database.createSchema(new DataSourceBuilder(database, config.database()).build(), config.schema(), config.role())
        .timeout(Duration.ofSeconds(30))
        .block();
  }

  public static StateMachine stateMachine(Config config) {
    return stateMachine(config, dataSource(config));
  }

  /// The pool of connections to the database, which the state machine and the backoffice share
  public static DataSource dataSource(Config config) {
    return new DataSourceBuilder(database, config.database()).buildPooled();
  }

  public static StateMachine stateMachine(Config config, DataSource dataSource) {
    return new StateMachine(
        null,
        Greetings.transitions(),
        dataSource,
        null, // The schema is created by the migrate admin process
        config.schema(),
        null,
        Clock.systemUTC(),
        // Writes the events to stdout, one line per event, and leaves routing and storage of the stream to the
        // execution environment (12-factor XI)
        new JsonLinesListener()
    );
  }

  public static DisposableServer serve(StateMachine stateMachine, int port) {
    return serve(stateMachine, port, Backoffice.none());
  }

  public static DisposableServer serve(StateMachine stateMachine, int port, Backoffice backoffice) {
    return new HttpInboxListener(stateMachine)
        .filter((request, next) -> isHealthCheck(request.method(), request.uri().getPath())
            ? Mono.just(new HttpResponseMessage(200, "OK", Map.of("Content-Type", "text/plain"), "OK".getBytes(StandardCharsets.UTF_8)))
            : next.apply(request))
        .filter(backoffice)
        .listen(port);
  }

  private static boolean isHealthCheck(Method method, String path) {
    return method == Method.GET && path.equals("/health");
  }

}
