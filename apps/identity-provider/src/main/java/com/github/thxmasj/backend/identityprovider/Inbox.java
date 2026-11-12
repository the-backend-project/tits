package com.github.thxmasj.backend.identityprovider;

import static com.github.thxmasj.backend.core.Validated.invalid;
import static com.github.thxmasj.backend.core.Validated.valid;
import static com.github.thxmasj.backend.http.inbox.HttpInbox.EntityModels.RequestDispatching;
import static com.github.thxmasj.backend.http.inbox.HttpInbox.EntityModels.RequestRouting;
import static com.github.thxmasj.backend.http.inbox.HttpInbox.MessageIds;
import static com.github.thxmasj.backend.http.inbox.HttpRequestRoute.ContentRoute.anyContent;
import static com.github.thxmasj.backend.http.inbox.HttpRequestRoute.ContentRoute.newEntity;
import static com.github.thxmasj.backend.http.inbox.TransitionModels.requestDispatchingTransitions;
import static com.github.thxmasj.backend.http.inbox.TransitionModels.requestRoutingTransitions;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.github.thxmasj.backend.core.DataType;
import com.github.thxmasj.backend.core.EntityModel;
import com.github.thxmasj.backend.core.State;
import com.github.thxmasj.backend.core.TransitionModelBuilder.TransitionModel;
import com.github.thxmasj.backend.core.Validated;
import com.github.thxmasj.backend.core.message.http.HttpRequestMessage;
import com.github.thxmasj.backend.core.message.http.HttpRequestMessage.Method;
import com.github.thxmasj.backend.core.message.http.HttpResponseMessage;
import com.github.thxmasj.backend.http.inbox.HttpInbox.CustomResponse;
import com.github.thxmasj.backend.http.inbox.HttpRequestRoute;
import com.github.thxmasj.backend.http.inbox.ResponseEventType;
import com.github.thxmasj.backend.identityprovider.Clients.Registration;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

/// The HTTP requests handled by the state machine. They arrive through the [Edge], which authenticates them and puts
/// what it found in trusted headers, so no credential ever enters the event log.
public interface Inbox {

  /// The client id of the client requesting a token, which is yet to be authenticated
  String ClientIdHeader = "X-Idp-Client-Id";
  /// The hash of the secret of the client requesting a token, or of the secret generated for a client being registered
  String SecretHashHeader = "X-Idp-Client-Secret-Hash";
  /// The client id of the authenticated client registering a client
  String RegisteredByHeader = "X-Idp-Registered-By";
  List<String> trustedHeaders = List.of(ClientIdHeader, SecretHashHeader, RegisteredByHeader);

  /// A JSON response to a request
  record Reply(int status, String body) {

    static Reply json(int status, Object body) {
      return new Reply(status, DataType.objectMapper.writeValueAsString(body));
    }

    static Reply error(int status, String error, String description) {
      var body = new LinkedHashMap<String, String>();
      body.put("error", error);
      body.put("error_description", description);
      return json(status, body);
    }

  }

  ResponseEventType<Reply> SendReply = new ResponseEventType<>(
      "Reply",
      UUID.fromString("9e5b3d1f-7a28-4c64-8f0b-2d6e9a4c1b57"),
      DataType.json(Reply.class)
  );

  List<HttpRequestRoute<?>> routes = List.of(
      new HttpRequestRoute<>(
          m -> m.method() == Method.POST && m.uri().getPath().equals("/token"),
          Inbox::tokenRequest,
          List.of(anyContent(
              null,
              null,
              UUID.fromString("d1c8f5a2-3e97-4b06-a4d1-5f2b8e7c9a63"),
              Tokens.RequestToken,
              Tokens.TokenRequest,
              r -> r.body(),
              newEntity()
          ))
      ),
      new HttpRequestRoute<>(
          m -> m.method() == Method.POST && m.uri().getPath().equals("/clients"),
          Inbox::registration,
          List.of(anyContent(
              null,
              (m, _) -> Validated.validElse(m.headerValue(RegisteredByHeader), "Unauthenticated"),
              UUID.fromString("f7a2c6e9-4d15-4b83-9e0a-1c5d8b3f6e24"),
              Clients.RequestRegistration,
              Clients.ClientRegistration,
              r -> r.body(),
              newEntity()
          ))
      )
  );

  List<CustomResponse<?>> customResponses = List.of(
      new CustomResponse<>(
          SendReply,
          c -> new HttpResponseMessage(
              c.input().status(),
              reasonPhrase(c.input().status()),
              Map.of(
                  "Content-Type", "application/json;charset=UTF-8",
                  // Responses with credentials must not be cached, RFC 6749 section 5.1
                  "Cache-Control", "no-store",
                  "X-Correlation-Id", c.correlationId()
              ),
              c.input().body().getBytes(UTF_8)
          )
      )
  );

  static Map<EntityModel, Map<State, List<TransitionModel<?, ?>>>> transitions(Duration tokenLifetime) {
    var transitions = new HashMap<EntityModel, Map<State, List<TransitionModel<?, ?>>>>();
    transitions.putAll(Clients.transitions());
    transitions.putAll(Tokens.transitions(tokenLifetime));
    transitions.put(RequestRouting, requestRoutingTransitions(routes));
    transitions.put(RequestDispatching, requestDispatchingTransitions(routes, customResponses));
    transitions.put(MessageIds, MessageIds.transitions());
    return transitions;
  }

  /// The form is validated by the edge, which also authenticates the client
  private static Validated<Tokens.Request> tokenRequest(HttpRequestMessage request) {
    String clientId = request.headerValue(ClientIdHeader);
    String secretHash = request.headerValue(SecretHashHeader);
    if (clientId == null || secretHash == null)
      return invalid("Client credentials missing");
    try {
      return valid(new Tokens.Request(clientId, secretHash, Form.parse(request.body()).get("scope")));
    } catch (IllegalArgumentException e) {
      return invalid(e.getMessage());
    }
  }

  /// A registration request, e.g. `{"client_id": "orders", "scope": "orders:read orders:write"}`, with an optional
  /// `subject` for the `sub` of the client's tokens. A client without `scope` has no scopes.
  private static Validated<Registration> registration(HttpRequestMessage request) {
    String registeredBy = request.headerValue(RegisteredByHeader);
    String secretHash = request.headerValue(SecretHashHeader);
    if (registeredBy == null || secretHash == null)
      return invalid("Unauthenticated");
    JsonNode body;
    try {
      body = request.body() != null ? DataType.objectMapper.readTree(request.body()) : null;
    } catch (JacksonException e) {
      return invalid("Body is not JSON");
    }
    if (body == null || !body.isObject())
      return invalid("Body is not a JSON object");
    JsonNode clientId = body.get("client_id");
    if (clientId == null || !clientId.isString() || !Clients.isValidClientId(clientId.asString()))
      return invalid("client_id must be 1 to 100 of the characters A-Z, a-z, 0-9, '.', '_', '~' and '-'");
    // A client without scopes, which is imported, has no scope
    JsonNode scope = body.get("scope");
    if (scope != null && (!scope.isString() || !Scopes.isValid(scope.asString())))
      return invalid("scope must be a space separated list of scopes");
    JsonNode subject = body.get("subject");
    if (subject != null && (!subject.isString() || !Clients.isValidSubject(subject.asString())))
      return invalid("subject must be a string of 1 to " + Clients.maxSubjectLength + " characters, not blank and without control characters");
    return valid(new Registration(
        clientId.asString(),
        subject != null ? subject.asString() : null,
        secretHash,
        scope != null ? Scopes.parse(scope.asString()) : List.of(),
        registeredBy
    ));
  }

  private static String reasonPhrase(int status) {
    return switch (status) {
      case 200 -> "OK";
      case 201 -> "Created";
      case 400 -> "Bad Request";
      case 401 -> "Unauthorized";
      case 409 -> "Conflict";
      default -> "Status " + status;
    };
  }

}
