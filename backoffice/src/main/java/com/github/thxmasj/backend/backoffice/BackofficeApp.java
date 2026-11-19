package com.github.thxmasj.backend.backoffice;

import com.github.thxmasj.backend.core.EntityModel;
import com.github.thxmasj.backend.core.JsonLinesListener;
import com.github.thxmasj.backend.core.StateMachine;
import com.github.thxmasj.backend.core.database.DataSourceBuilder;
import com.github.thxmasj.backend.core.database.Database;
import com.github.thxmasj.backend.core.database.DatabaseClient;
import com.github.thxmasj.backend.http.inbox.HttpInboxListener;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import reactor.netty.DisposableServer;

/// Runs the backoffice of an application as an application of its own, following the conventions of the apps: it is
/// configured by environment variables, serves HTTP on `$PORT` until terminated, and has a migrate admin process.
///
/// An application's backoffice is a module of its own, depending on the application for its entity models, whose main
/// method is one line:
///
/// ```java
/// public static void main(String[] args) {
///   BackofficeApp.run("Orders", "orders", Orders.transitions(), args, System.getenv());
/// }
/// ```
///
/// - `serve` (the default) serves the backoffice, with its sessions in its own schema, `DATABASE_SCHEMA`, and the
///   entities of the application read from the application's schema, `APPLICATION_SCHEMA`.
/// - `migrate`, as an administrator, creates the backoffice's schema for the role `DATABASE_ROLE`, and grants the role
///   read access to the application's schema, which the application's migrate admin process created.
public final class BackofficeApp {

  private BackofficeApp() {}

  /// What the backoffice app is configured with, by environment variables (12-factor III)
  ///
  /// @param database          the name of the database, `mssql` or `pgsql`
  /// @param schema            the backoffice's own schema, with its sessions
  /// @param applicationSchema the application's schema, which the backoffice reads
  /// @param role              the role given access to the backoffice's schema and read access to the application's.
  ///                          Required by `migrate`.
  /// @param backoffice        how users sign in. Required to serve.
  public record Config(
      int port,
      String database,
      DatabaseClient.Config databaseConfig,
      String schema,
      String applicationSchema,
      String role,
      Backoffice.Config backoffice
  ) {

    /// @param defaultApplicationSchema the application's default schema. The backoffice's own schema defaults to it
    ///                                 with `_backoffice` added.
    public static Config fromEnvironment(Map<String, String> env, String defaultApplicationSchema, boolean serving) {
      String database = env.getOrDefault("DATABASE", "mssql");
      String applicationSchema = env.getOrDefault("APPLICATION_SCHEMA", defaultApplicationSchema);
      return new Config(
          Integer.parseInt(env.getOrDefault("PORT", "8080")),
          database,
          DatabaseClient.Config.fromEnvironment(env, database),
          env.getOrDefault("DATABASE_SCHEMA", applicationSchema + "_backoffice"),
          applicationSchema,
          env.get("DATABASE_ROLE"),
          serving ? Backoffice.Config.fromEnvironment(env) : null
      );
    }

  }

  /// Runs the command of the arguments, `serve` or `migrate`. Exits the process if serving fails to start.
  ///
  /// @param service                  the name of the application, e.g. `Identity provider`
  /// @param defaultApplicationSchema the application's default schema, e.g. `identity_provider`
  /// @param applicationModels        the entity models of the application's state machine
  public static void run(
      String service,
      String defaultApplicationSchema,
      List<? extends EntityModel> applicationModels,
      String[] args,
      Map<String, String> env
  ) {
    long t0 = System.nanoTime();
    String command = args.length == 0 ? "serve" : args[0];
    switch (command) {
      case "serve" -> {
        DisposableServer server;
        StateMachine stateMachine;
        try {
          Config config = Config.fromEnvironment(env, defaultApplicationSchema, true);
          DataSource dataSource = new DataSourceBuilder(Database.named(config.database()), config.databaseConfig()).buildPooled();
          Backoffice backoffice = backoffice(service, config, dataSource, applicationModels);
          stateMachine = stateMachine(config, dataSource, backoffice);
          server = serve(config, stateMachine, backoffice);
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
        Config config = Config.fromEnvironment(env, defaultApplicationSchema, false);
        migrate(config);
        System.out.println("Migrated schema " + config.schema() + ", with read access to " + config.applicationSchema());
      }
      default -> {
        System.err.println("Usage: backoffice [serve|migrate]");
        System.exit(2);
      }
    }
  }

  /// Creates the backoffice's schema if it does not exist, and fails if it exists with a different structure, and
  /// grants the role read access to the application's schema
  public static void migrate(Config config) {
    if (config.role() == null)
      throw new IllegalArgumentException("Environment variable DATABASE_ROLE is required for migration");
    Database database = Database.named(config.database());
    DataSource dataSource = new DataSourceBuilder(database, config.databaseConfig()).build();
    database.createSchema(dataSource, config.schema(), config.role())
        .then(database.grantRead(dataSource, config.applicationSchema(), config.role()))
        .timeout(Duration.ofSeconds(30))
        .block();
  }

  /// The model of the backoffice, reading the application's schema
  public static Backoffice backoffice(
      String service,
      Config config,
      DataSource dataSource,
      List<? extends EntityModel> applicationModels
  ) {
    return new Backoffice(service, config.backoffice(), Database.of(dataSource).client(dataSource), config.applicationSchema(), applicationModels);
  }

  /// The backoffice's own state machine, of the model of the backoffice, in the backoffice's schema
  public static StateMachine stateMachine(Config config, DataSource dataSource, Backoffice backoffice) {
    return new StateMachine(
        null,
        backoffice.inbox().entityModels(),
        dataSource,
        null, // The schema is created by the migrate admin process
        config.schema(),
        null,
        Clock.systemUTC(),
        // Writes the events to stdout for monitoring, e.g. the refused sign-ins as warnings
        new JsonLinesListener()
    );
  }

  /// Serves the backoffice: every request goes to the inbox of its state machine
  public static DisposableServer serve(Config config, StateMachine stateMachine, Backoffice backoffice) {
    return new HttpInboxListener(stateMachine, backoffice.inbox()).listen(config.port());
  }

}
