package com.github.thxmasj.backend.core.test;

import com.github.thxmasj.backend.core.EntityModel;
import com.github.thxmasj.backend.core.State;
import com.github.thxmasj.backend.core.StateMachine;
import com.github.thxmasj.backend.core.TransitionModelBuilder.TransitionModel;
import com.github.thxmasj.backend.core.database.Client.Config;
import com.github.thxmasj.backend.core.database.Database;
import com.github.thxmasj.backend.core.database.Storage;
import com.github.thxmasj.backend.core.database.jdbc.DataSourceBuilder;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import javax.sql.DataSource;
import reactor.core.publisher.Mono;

public class Init {

  // The tests run on SQL Server by default, and on PostgreSQL with -Ddatabase=pgsql
  private static final Database database = Database.named(System.getProperty("database", "mssql"));
  private static final boolean pgsql = database.name().equals("pgsql");
  private static final DataSource dataSource = new DataSourceBuilder(database, databaseConfig("testlogin", "Please_hide_me!")).build();
  private static final DataSource migrationDataSource = new DataSourceBuilder(database, databaseConfig(
      pgsql ? "postgres" : "sa",
      "A_Str0ng_Required_Password"
  )).build();

  public static DataSource dataSource() {
    return dataSource;
  }

  public static DataSource migrationDataSource() {
    return migrationDataSource;
  }

  public static boolean pgsql() {
    return pgsql;
  }

  /// The storage of a schema in the test database
  public static Storage storage(String schemaName, Map<EntityModel, Map<State, List<TransitionModel<?, ?>>>> transitions) {
    return database.storage(dataSource, transitions, schemaName, Clock.systemUTC());
  }

  public static StateMachine stateMachine(
      String schemaName,
      Map<EntityModel, Map<State, List<TransitionModel<?, ?>>>> transitions
  ) {
    return stateMachine(schemaName, transitions, Clock.systemUTC());
  }

  public static StateMachine stateMachine(
      String schemaName,
      Map<EntityModel, Map<State, List<TransitionModel<?, ?>>>> transitions,
      Clock clock
  ) {
    return new StateMachine(
        _ -> Mono.empty(),
        transitions,
        dataSource,
        migrationDataSource,
        schemaName,
        "Test", // role
        clock,
        new Logger("Test")
    );
  }

  public static StateMachine stateMachine(
      Map<EntityModel, Map<State, List<TransitionModel<?, ?>>>> transitions
  ) {
    return stateMachine(UUID.randomUUID().toString(), transitions);
  }

  public static HttpServer httpServer() throws IOException {
    long t0 = System.currentTimeMillis();
    var server = HttpServer.create(new InetSocketAddress(0), 1);
    server.start();
    long t1 = System.currentTimeMillis();
    System.out.println("HTTP server started on port " + server.getAddress().getPort() + " in " + (t1 - t0) + "ms");
    return server;
  }

  public static HttpContext addDelayContext(HttpServer server, String path, Function<URI, Duration> delay) {
    return server.createContext(
        path,
        exchange -> {
          try {
            Thread.sleep(delay.apply(exchange.getRequestURI()));
          } catch (InterruptedException e) {
            throw new RuntimeException(e);
          }
          exchange.sendResponseHeaders(200, 0);
          exchange.getResponseBody().close();
          exchange.close();
        }
    );
  }

  public static void addDelayContext(HttpServer server, String path, Duration delay) {
    server.createContext(
        path,
        exchange -> {
          try {
            Thread.sleep(delay);
          } catch (InterruptedException e) {
            throw new RuntimeException(e);
          }
          exchange.sendResponseHeaders(200, 0);
          exchange.getResponseBody().close();
          exchange.close();
        }
    );
  }

  public static HttpContext addOkContext(HttpServer server, String path) {
    return server.createContext(
        path,
        exchange -> {
          exchange.sendResponseHeaders(200, 0);
          exchange.getResponseBody().close();
          exchange.close();
        }
    );
  }

  public static HttpContext addBadRequestContext(HttpServer server, String path) {
    return server.createContext(
        path,
        exchange -> {
          exchange.sendResponseHeaders(400, 0);
          exchange.getResponseBody().close();
          exchange.close();
        }
    );
  }

  public static HttpContext addInternalServerErrorContext(HttpServer server, String path) {
    return server.createContext(
        path,
        exchange -> {
          exchange.sendResponseHeaders(500, 0);
          exchange.getResponseBody().close();
          exchange.close();
        }
    );
  }

  private static Config databaseConfig(String username, String password) {
    return new Config(
        "localhost",
        pgsql ? 15432 : 11433,
        "work",
        username,
        password,
        true,
        10,
        Duration.ofSeconds(10)
    );
  }

}
