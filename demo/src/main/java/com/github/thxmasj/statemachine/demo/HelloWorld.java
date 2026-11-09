package com.github.thxmasj.statemachine.demo;

import static com.github.thxmasj.statemachine.EntityModel.Begin;
import static com.github.thxmasj.statemachine.EntitySelector.entityIdFromSession;
import static com.github.thxmasj.statemachine.TransitionModelBuilder.WithEvent.onEvent;
import static com.github.thxmasj.statemachine.Validated.valid;
import static com.github.thxmasj.statemachine.http.inbox.HttpInbox.EntityModels.RequestDispatching;
import static com.github.thxmasj.statemachine.http.inbox.HttpInbox.EntityModels.RequestRouting;
import static com.github.thxmasj.statemachine.http.inbox.HttpRequestRoute.ContentRoute.anyContent;
import static com.github.thxmasj.statemachine.http.inbox.HttpRequestRoute.ContentRoute.newEntity;
import static com.github.thxmasj.statemachine.http.inbox.TransitionModels.requestDispatchingTransitions;
import static com.github.thxmasj.statemachine.http.inbox.TransitionModels.requestRoutingTransitions;

import com.github.thxmasj.statemachine.BasicEventType;
import com.github.thxmasj.statemachine.DataType;
import com.github.thxmasj.statemachine.EntityModel;
import com.github.thxmasj.statemachine.EventType;
import com.github.thxmasj.statemachine.State;
import com.github.thxmasj.statemachine.TransitionModelBuilder.TransitionModel;
import com.github.thxmasj.statemachine.http.inbox.HttpInbox.CustomResponse;
import com.github.thxmasj.statemachine.http.inbox.HttpRequestRoute;
import com.github.thxmasj.statemachine.http.inbox.ResponseEventType;
import com.github.thxmasj.statemachine.message.http.HttpResponseMessage;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/// A greeting is an entity which is created for every `GET /`. Its creation responds with a hello world page.
public interface HelloWorld {

  EntityModel Greeting = EntityModel.of("Greeting", UUID.fromString("8a4c1d36-4f0e-4b8e-9a47-2f5f0c0b6d11"));

  enum States implements State {
    Greeted
  }

  EventType<Void, Void> Greet = BasicEventType.of("Greet", UUID.fromString("0b7f8f43-37a3-4f26-9a5c-6f3ad6f1c2a4"));

  ResponseEventType<String> RespondGreeting = new ResponseEventType<>(
      "Respond greeting",
      UUID.fromString("d2c9b8f1-6a0e-4a43-8f8e-1c0f6e3b7a52"),
      DataType.string()
  );

  List<HttpRequestRoute<?>> routes = List.of(
      new HttpRequestRoute<>(
          m -> m.requestLine().matches("GET /(\\?.*)?"),
          _ -> valid(null),
          List.of(anyContent(
              null,
              null,
              UUID.fromString("5e3d4a8c-0f2b-4c6e-b1d7-9a8f7e6d5c4b"),
              Greet,
              Greeting,
              _ -> null,
              newEntity()
          ))
      )
  );

  List<CustomResponse<?>> customResponses = List.of(
      new CustomResponse<>(
          RespondGreeting,
          c -> new HttpResponseMessage(
              200,
              "OK",
              Map.of("Content-Type", "text/html;charset=UTF-8", "X-Correlation-Id", c.correlationId()),
              page(c.input(), c.correlationId()).getBytes(StandardCharsets.UTF_8)
          )
      )
  );

  static Map<EntityModel, Map<State, List<TransitionModel<?, ?>>>> transitions() {
    return Map.of(
        Greeting, Map.of(
            Begin, List.of(
                onEvent(Greet).to(States.Greeted)
                    .assemble(c -> c.log().entityId().value().toString())
                    .trigger(RespondGreeting).with(id -> id).on(RequestDispatching).identifiedBy(entityIdFromSession())
                    .output()
            ),
            States.Greeted, List.of()
        ),
        RequestRouting, requestRoutingTransitions(routes),
        RequestDispatching, requestDispatchingTransitions(routes, customResponses)
    );
  }

  private static String page(String greetingId, String correlationId) {
    return """
        <!DOCTYPE html>
        <html lang="en">
        <head><meta charset="utf-8"><title>Hello, world!</title></head>
        <body>
        <h1>Hello, world!</h1>
        <p>Greeting <code>%s</code>, correlation id <code>%s</code>.</p>
        </body>
        </html>
        """.formatted(escape(greetingId), escape(correlationId));
  }

  /// The correlation id is taken from a request header, so it must not be trusted
  private static String escape(String s) {
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
  }

}
