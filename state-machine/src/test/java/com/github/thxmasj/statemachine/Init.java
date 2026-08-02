package com.github.thxmasj.statemachine;

import static com.github.thxmasj.statemachine.http.inbox.HttpInbox.EntityModels.RequestDispatching;
import static com.github.thxmasj.statemachine.http.inbox.HttpInbox.EntityModels.RequestRouting;
import static com.github.thxmasj.statemachine.http.inbox.TransitionModels.requestDispatchingTransitions;
import static com.github.thxmasj.statemachine.http.inbox.TransitionModels.requestRoutingTransitions;

import com.github.thxmasj.statemachine.TransitionModelBuilder.TransitionModel;
import com.github.thxmasj.statemachine.database.Client.Config;
import com.github.thxmasj.statemachine.database.jdbc.DataSourceBuilder;
import com.github.thxmasj.statemachine.http.inbox.HttpRequestRoute;
import com.github.thxmasj.statemachine.http.outbox.HttpOutbox;
import com.github.thxmasj.statemachine.http.outbox.HttpOutbox.CustomRequest;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import javax.sql.DataSource;
import reactor.core.publisher.Mono;

public class Init {

  private static final DataSource dataSource = new DataSourceBuilder(databaseConfig("testlogin", "Please_hide_me!")).build();
  private static final DataSource migrationDataSource = new DataSourceBuilder(databaseConfig("sa", "A_Str0ng_Required_Password")).build();

  public static StateMachine stateMachine(
      EntityModel entityModel,
      Map<State, List<TransitionModel<?, ?>>> processTransitions,
      List<HttpRequestRoute<?>> routes,
      List<CustomRequest<?, ?, ?>> outboxRequests
  ) {
    Map<EntityModel, Map<State, List<TransitionModel<?, ?>>>> transitions = new HashMap<>();
    transitions.put(entityModel, processTransitions);
    transitions.put(RequestRouting, requestRoutingTransitions(routes));
    transitions.put(RequestDispatching, requestDispatchingTransitions(routes, List.of()));
    for (var outboxRequest : outboxRequests) {
      transitions.put(outboxRequest.outboxModel(), HttpOutbox.transitions(outboxRequest));
    }
    return stateMachine(transitions);
  }

  public static StateMachine stateMachine(
      Map<EntityModel, Map<State, List<TransitionModel<?, ?>>>> transitions
  ) {
    return new StateMachine(
        _ -> Mono.empty(),
        transitions,
        dataSource,
        migrationDataSource,
        UUID.randomUUID().toString(), // schema name
        "Test", // role
        Clock.systemUTC(),
        new Logger("Test")
    );
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

}
