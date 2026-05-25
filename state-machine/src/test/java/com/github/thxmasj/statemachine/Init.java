package com.github.thxmasj.statemachine;

import com.github.thxmasj.statemachine.TransitionModelBuilder.TransitionModel;
import com.github.thxmasj.statemachine.database.Client.Config;
import com.github.thxmasj.statemachine.database.jdbc.DataSourceBuilder;
import com.github.thxmasj.statemachine.http.HttpClient;
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
import com.github.thxmasj.statemachine.http.HttpRequestRouter.HttpRequestRoute;
import com.github.thxmasj.statemachine.http.NettyHttpClient;
import com.github.thxmasj.statemachine.http.NettyHttpClientBuilder;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpServer;
import reactor.core.publisher.Mono;

import static com.github.thxmasj.statemachine.BuiltinEntities.Models.RequestDispatching;
import static com.github.thxmasj.statemachine.BuiltinEntities.Models.RequestRouting;
import static com.github.thxmasj.statemachine.BuiltinEntities.requestDispatchingTransitions;
import static com.github.thxmasj.statemachine.BuiltinEntities.requestRoutingTransitions;
import static com.github.thxmasj.statemachine.HttpInboxTest.Queues.DeviceListener;

public class Init {

  private static final DataSource dataSource = new DataSourceBuilder(databaseConfig("testlogin", "Please_hide_me!")).build();
  private static final DataSource migrationDataSource = new DataSourceBuilder(databaseConfig("sa", "A_Str0ng_Required_Password")).build();

  public static StateMachine stateMachine(
      EntityModel entityModel,
      Map<State, List<TransitionModel<?, ?>>> processTransitions,
      List<HttpRequestRoute<?>> routes,
      Function<OutboxQueue, HttpClient> outbox
  ) {
    Map<EntityModel, Map<State, List<TransitionModel<?, ?>>>> transitions = new HashMap<>();
    transitions.put(entityModel, processTransitions);
    transitions.put(RequestRouting, requestRoutingTransitions(routes));
    transitions.put(RequestDispatching, requestDispatchingTransitions(routes, List.of()));
    return stateMachine(transitions, outbox);
  }

  public static StateMachine stateMachine(
      Map<EntityModel, Map<State, List<TransitionModel<?, ?>>>> transitions,
      Function<OutboxQueue, HttpClient> outbox
  ) {
    return new StateMachine(
        null,
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
        new Logger("Test"),
        outbox
    );
  }

  public static HttpServer httpServer() throws IOException {
    var server = HttpServer.create(new InetSocketAddress(0), 1);
    server.start();
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
