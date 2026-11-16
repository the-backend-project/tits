package com.github.thxmasj.backend.helloworld;

import static com.github.thxmasj.backend.core.EntityModel.Begin;
import static com.github.thxmasj.backend.core.EntitySelector.entityIdFromSession;
import static com.github.thxmasj.backend.core.TransitionModelBuilder.WithEvent.onEvent;
import static com.github.thxmasj.backend.http.inbox.HttpInbox.Request;
import static com.github.thxmasj.backend.http.inbox.HttpInboxRequest.newEntity;

import com.github.thxmasj.backend.backoffice.Backoffice;
import com.github.thxmasj.backend.core.BasicEventType;
import com.github.thxmasj.backend.core.DataType;
import com.github.thxmasj.backend.core.EntityModel;
import com.github.thxmasj.backend.core.EventType;
import com.github.thxmasj.backend.core.State;
import com.github.thxmasj.backend.core.TransitionModel;
import com.github.thxmasj.backend.http.inbox.HttpInbox;
import com.github.thxmasj.backend.http.inbox.HttpInboxRequest;
import com.github.thxmasj.backend.http.inbox.ResponseEventType;
import com.github.thxmasj.backend.core.message.http.HttpResponseMessage;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/// A greeting is an entity which is created for every `GET /`. Its creation responds with a hello world page. The
/// transitions include those of the sessions of the [Backoffice], which is served under `/backoffice`.
public interface Greetings {

  EntityModel Greeting = EntityModel.of("Greeting", UUID.fromString("8a4c1d36-4f0e-4b8e-9a47-2f5f0c0b6d11"));

  enum States implements State {
    Greeted
  }

  EventType<Void, Void> GreetingRequested = BasicEventType.of("Greeting requested", UUID.fromString("0b7f8f43-37a3-4f26-9a5c-6f3ad6f1c2a4"));

  ResponseEventType<String> GreetingResponseCreated = new ResponseEventType<>(
      "Greeting response created",
      UUID.fromString("d2c9b8f1-6a0e-4a43-8f8e-1c0f6e3b7a52"),
      DataType.string()
  );

  /// A `GET /`, which creates a greeting and is answered with its page
  HttpInboxRequest<Void> GreetingRequest = HttpInboxRequest
      .matching(m -> m.requestLine().matches("GET /(\\?.*)?"))
      .noContent()
      .process(GreetingRequested, Greeting, newEntity())
      .response(
          GreetingResponseCreated,
          c -> new HttpResponseMessage(
              200,
              "OK",
              Map.of("Content-Type", "text/html;charset=UTF-8", "X-Correlation-Id", c.correlationId()),
              page(c.input(), c.correlationId()).getBytes(StandardCharsets.UTF_8)
          )
      )
      .build("GreetingRequest", UUID.fromString("5e3d4a8c-0f2b-4c6e-b1d7-9a8f7e6d5c4b"));

  static Map<EntityModel, Map<State, List<TransitionModel<?, ?>>>> transitions() {
    var application = new HashMap<EntityModel, Map<State, List<TransitionModel<?, ?>>>>(Backoffice.transitions());
    application.put(
        Greeting, Map.of(
            Begin, List.of(
                onEvent(GreetingRequested).to(States.Greeted)
                    .assemble(c -> c.log().entityId().toString())
                    .trigger(GreetingResponseCreated).with(id -> id).on(Request).identifiedBy(entityIdFromSession())
                    .output()
            ),
            States.Greeted, List.of()
        )
    );
    var requests = new ArrayList<HttpInboxRequest<?>>(List.of(GreetingRequest));
    requests.addAll(Backoffice.requests());
    return HttpInbox.transitions(requests, application);
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
