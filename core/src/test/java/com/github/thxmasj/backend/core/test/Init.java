package com.github.thxmasj.backend.core.test;

import com.github.thxmasj.backend.core.EntityModel;
import com.github.thxmasj.backend.core.EventType;
import com.github.thxmasj.backend.core.State;
import com.github.thxmasj.backend.core.StateMachine;
import com.github.thxmasj.backend.core.TransitionModelBuilder.TransitionModel;
import com.github.thxmasj.backend.core.database.Client.Config;
import com.github.thxmasj.backend.core.database.Dialect;
import com.github.thxmasj.backend.core.database.jdbc.DataSourceBuilder;
import com.github.thxmasj.backend.core.database.mssql.EntitiesByIndex;
import com.github.thxmasj.backend.core.database.mssql.EventsByEntityId;
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

  // The tests run on SQL Server by default, and on PostgreSQL with -Ddatabase=postgres
  private static final Dialect dialect =
      "postgres".equals(System.getProperty("database")) ? Dialect.POSTGRES : Dialect.SQL_SERVER;
  private static final DataSource dataSource = new DataSourceBuilder(databaseConfig("testlogin", "Please_hide_me!")).build();
  private static final DataSource migrationDataSource = new DataSourceBuilder(databaseConfig(
      dialect == Dialect.POSTGRES ? "postgres" : "sa",
      "A_Str0ng_Required_Password"
  )).build();

  public static DataSource dataSource() {
    return dataSource;
  }

  public static DataSource migrationDataSource() {
    return migrationDataSource;
  }

  /// Events by entity id in the dialect of the test database
  public static EventsByEntityId eventsByEntityId(List<EntityModel> entityModels, String schemaName) {
    return switch (dialect) {
      case SQL_SERVER -> new EventsByEntityId(dataSource, entityModels, schemaName, Clock.systemUTC());
      case POSTGRES -> new com.github.thxmasj.backend.core.database.postgres.EventsByEntityId(dataSource, entityModels, schemaName, Clock.systemUTC());
    };
  }

  /// Entities by index in the dialect of the test database
  public static EntitiesByIndex entitiesByIndex(Map<EntityModel, List<EventType<?, ?>>> eventTypesByEntityModel, String schemaName) {
    return switch (dialect) {
      case SQL_SERVER -> new EntitiesByIndex(dataSource, eventTypesByEntityModel, schemaName, Clock.systemUTC());
      case POSTGRES -> new com.github.thxmasj.backend.core.database.postgres.EntitiesByIndex(dataSource, eventTypesByEntityModel, schemaName, Clock.systemUTC());
    };
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
        dialect == Dialect.POSTGRES ? 15432 : 11433,
        "work",
        username,
        password,
        true,
        10,
        Duration.ofSeconds(10),
        dialect
    );
  }

}
