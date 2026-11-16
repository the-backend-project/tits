package com.github.thxmasj.backend.http.inbox;

import static com.github.thxmasj.backend.core.EntitySelector.newEntityId;

import com.github.thxmasj.backend.core.BasicEventType;
import com.github.thxmasj.backend.core.DataType;
import com.github.thxmasj.backend.core.EntityModel;
import com.github.thxmasj.backend.core.EntityModelGroup;
import com.github.thxmasj.backend.core.EntitySelector;
import com.github.thxmasj.backend.core.EventReference;
import com.github.thxmasj.backend.core.EventTrigger;
import com.github.thxmasj.backend.core.EventTrigger.EventSpec;
import com.github.thxmasj.backend.core.EventType;
import com.github.thxmasj.backend.core.IdEntityModel;
import com.github.thxmasj.backend.core.State;
import com.github.thxmasj.backend.core.TransitionModel;
import com.github.thxmasj.backend.core.Tuples.Tuple2;
import com.github.thxmasj.backend.core.Tuples.Tuple3;
import com.github.thxmasj.backend.core.Validated;
import com.github.thxmasj.backend.core.http.HttpDataType;
import com.github.thxmasj.backend.core.message.http.HttpRequestMessage;
import com.github.thxmasj.backend.core.message.http.HttpResponseMessage;
import com.github.thxmasj.backend.http.inbox.Authorization.Refused;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;

/// The HTTP inbox of an application: the [HttpInboxRequest]s it receives, and the `RequestRouting` entity model
/// routing a received request to the inbox request matching it.
public interface HttpInbox {

  /// Routes every received request to the inbox request matching it, and answers the request with the response the
  /// inbox request gives it. A request matching no inbox request is answered with 400 Bad Request.
  EntityModel RequestRouting = EntityModel.of("RequestRouting", UUID.fromString("20755705-fc81-4228-a1a7-5e13d6e3c153"));

  /// Any inbox request, which an application responds to by triggering a [ResponseEventType] such as
  /// [#RequestCompleted] on it, identified by the session, without knowing which inbox request it is:
  ///
  /// ```java
  /// .trigger(RequestCompleted).with(d -> tuple("Done", d)).on(HttpInbox.Request).identifiedBy(entityIdFromSession())
  /// ```
  EntityModelGroup Request = EntityModelGroup.of(
      "Request",
      UUID.fromString("0f3b6e2a-9c41-4d7e-8a15-6b2f9d4c3e71"),
      model -> model instanceof HttpInboxRequest<?>
  );

  EventType<HttpRequestMessage, Void> RequestReceived = BasicEventType.of(
      "Request received",
      UUID.fromString("e55c0077-eddd-4840-b880-2bf0ace4468a"),
      HttpDataType.forRequest(),
      DataType.none()
  );

  /// Triggers a received request on a new `RequestRouting` entity
  EventTrigger<HttpRequestMessage, HttpRequestMessage, ?> TRIGGER = new EventTrigger<>(
      new EventSpec<>(RequestReceived, Function.identity()),
      List.of(newEntityId()),
      RequestRouting,
      false
  );

  /// The request is routed to the inbox request matching it
  EventType<HttpRequestMessage, HttpRequestMessage> RequestRouted = BasicEventType.of(
      "Request routed",
      UUID.fromString("5b0d7e3c-2a6f-4f81-9c4e-7d1a8b2e6f39"),
      HttpDataType.forRequest()
  );
  EventType<HttpRequestMessage, Void> RollbackRequestReceived = BasicEventType.of(
      "Rollback request received",
      UUID.fromString("c2e94f17-6b3a-4d58-a0e1-3f7c5b9d2a84"),
      HttpDataType.forRequest(),
      DataType.none()
  );
  EventType<RoutedRequest<?>, Void> RequestDispatched = BasicEventType.of(
      "Request dispatched",
      UUID.fromString("8a6c2d4f-1e3b-4a97-b5d8-0c9e7f2a1b63"),
      DataType.unknown(),
      DataType.none()
  );
  EventType<Tuple3<String, UUID, HttpRequestMessage>, HttpRequestMessage> RequestRejected = BasicEventType.of(
      "Request rejected",
      UUID.fromString("46106429-3c44-45cd-be04-5eb341c8f381"),
      DataType.tuple(DataType.string(), DataType.json(UUID.class), HttpDataType.forRequest()),
      HttpDataType.forRequest()
  );
  EventType<Tuple2<HttpRequestMessage, HttpResponseMessage>, HttpRequestMessage> DuplicateRequestRejected = BasicEventType.of(
      "Duplicate request rejected",
      UUID.fromString("304f5036-4ae6-406a-9512-f217e34e542b"),
      DataType.tuple(HttpDataType.forRequest(), HttpDataType.forResponse()),
      HttpDataType.forRequest()
  );
  EventType<Tuple2<RoutedRequest<?>, EventReference>, Tuple2<HttpRequestMessage, EventReference>> RequestAccepted = BasicEventType.of(
      "Request accepted",
      UUID.fromString("9615c3fb-4f15-47f5-b5d3-6149a6164d70"),
      DataType.unknown(),
      DataType.tuple(HttpDataType.forRequest(), DataType.eventReference())
  );
  EventType<Tuple3<String, RoutedRequest<?>, EventReference>, HttpRequestMessage> RollbackRequestAccepted = BasicEventType.of(
      "Rollback request accepted",
      UUID.fromString("abd02d03-8bdc-4bea-9135-214f31489965"),
      DataType.unknown(),
      HttpDataType.forRequest()
  );
  ResponseEventType<Tuple2<String, EventReference>> RequestCompleted = new ResponseEventType<>(
      "Request completed",
      UUID.fromString("fa608cc7-9a2b-42ec-ab83-5207ab3978a0"),
      DataType.tuple(DataType.string(), DataType.eventReference())
  );
  ResponseEventType<Tuple2<String, EventReference>> RollbackRequestCompleted = new ResponseEventType<>(
      "Rollback request completed",
      UUID.fromString("57fd7951-b922-42ff-99ed-6752f1d23024"),
      DataType.tuple(DataType.string(), DataType.eventReference())
  );
  EventType<HttpResponseMessage, HttpResponseMessage> DuplicatedRequestCompleted = BasicEventType.of(
      "Duplicated request completed",
      UUID.fromString("c448eb33-b5de-4201-9d58-55b9767727b6"),
      HttpDataType.forResponse()
  );
  ResponseEventType<Tuple2<String, EventReference>> InvalidRequestCompleted = new ResponseEventType<>(
      "Invalid request completed",
      UUID.fromString("6bc64696-3b9e-4897-b9d0-de3abf567685"),
      DataType.tuple(DataType.string(), DataType.eventReference())
  );
  ResponseEventType<Tuple2<String, EventReference>> FailedRequestCompleted = new ResponseEventType<>(
      "Failed request completed",
      UUID.fromString("4ea0817f-c153-4fbb-a09a-ffcd5e8d6960"),
      DataType.tuple(DataType.string(), DataType.eventReference())
  );
  EventType<HttpResponseMessage, HttpResponseMessage> RejectedRequestCompleted = BasicEventType.of(
      "Rejected request completed",
      UUID.fromString("887a0173-1847-4e5d-b529-3c25e1f8a5d1"),
      HttpDataType.forResponse(),
      HttpDataType.forResponse()
  );
  EventType<String, Void> RequestRejectedAsUnroutable = BasicEventType.of(
      "Request rejected as unroutable",
      UUID.fromString("9b6a57f8-8d3b-46b4-aeef-d8864f19fe13"),
      DataType.string(),
      DataType.none()
  );
  /// The request's body, message id or entity id is invalid
  EventType<String, HttpResponseMessage> RequestRejectedAsInvalid = BasicEventType.of(
      "Request rejected as invalid",
      UUID.fromString("e7a1c94b-3d2f-4b86-9f05-2a8d6c1e7b40"),
      DataType.string(),
      HttpDataType.forResponse()
  );
  EventType<Refused, HttpResponseMessage> RequestRejectedAsUnauthorized = BasicEventType.of(
      "Request rejected as unauthorized",
      UUID.fromString("3a204b0b-2740-4b61-bdfa-4149c819be04"),
      DataType.json(Refused.class),
      HttpDataType.forResponse()
  );
  EventType<HttpResponseMessage, HttpResponseMessage> ResponseCreated = BasicEventType.of(
      "Response created",
      UUID.fromString("2387a6a5-a483-428d-839f-26b5fe485247"),
      HttpDataType.forResponse(),
      HttpDataType.forResponse()
  );
  EventType<String, HttpResponseMessage> BadRequestResponseCreated = BasicEventType.of(
      "Bad request response created",
      UUID.fromString("a8225270-6a22-4f1f-ae17-5b5519d46df1"),
      DataType.string(),
      HttpDataType.forResponse()
  );
  EventType<MessageId, MessageId> MessageIdClaimed = BasicEventType.of(
      "Message id claimed",
      UUID.fromString("f721bf54-891b-41a0-aa63-5d3cbde52437"),
      new MessageIdDataType()
  );
  EventType<MessageId, MessageId> RollbackMessageIdClaimed = BasicEventType.of(
      "Rollback message id claimed",
      UUID.fromString("58cafda3-1818-4015-8f97-3d9f3bd2856f"),
      new MessageIdDataType()
  );
  /// Ids of the requests, by the entity id of their inbox request
  IdEntityModel MessageIds = IdEntityModel.of(
      "MessageIds",
      UUID.fromString("3c8e1f5a-7b2d-4e9c-a6f0-8d4b2e7c1a95"),
      MessageIdClaimed,
      RollbackMessageIdClaimed
  );

  /// The transitions of the application with its inbox, to create the state machine with: those of the application,
  /// and of `RequestRouting`, routing to the inbox requests, of the inbox requests and of `MessageIds`.
  ///
  /// Every inbox request is checked to take the responses its processes may give: the event types triggered on
  /// [#Request] by the entity models of its processes, or by entity models they trigger events on, must be
  /// [#RequestCompleted], [#InvalidRequestCompleted], [#FailedRequestCompleted] or one the request has a `response`
  /// for. A response it doesn't take would otherwise be rejected when it is given.
  ///
  /// @param application the transitions of the entity models of the application, e.g. those of the processes
  /// @throws IllegalArgumentException if an inbox request doesn't take a response its processes may give
  static Map<EntityModel, Map<State, List<TransitionModel<?, ?>>>> transitions(
      List<HttpInboxRequest<?>> requests,
      Map<EntityModel, Map<State, List<TransitionModel<?, ?>>>> application
  ) {
    var transitions = new HashMap<>(application);
    transitions.put(RequestRouting, requestRoutingTransitions(requests));
    for (HttpInboxRequest<?> request : requests)
      transitions.put(request, request.transitions());
    transitions.put(MessageIds, MessageIds.transitions());
    TransitionModels.checkResponses(requests, transitions);
    return transitions;
  }

  /// The transitions of `RequestRouting`, routing a request to the first of the inbox requests matching it
  static Map<State, List<TransitionModel<?, ?>>> requestRoutingTransitions(List<HttpInboxRequest<?>> requests) {
    return TransitionModels.requestRoutingTransitions(requests);
  }

  static <T> Function<HttpRequestMessage, Validated<T>> jsonParser(Class<T> contentType) {
    return new JsonRequestValidator<>(contentType, true);
  }

  static <T> Function<HttpRequestMessage, Validated<T>> jsonParser(Class<T> contentType, boolean validate) {
    return new JsonRequestValidator<>(contentType, validate);
  }

  /// An authorizer for requests requiring a bearer token, RFC 6750, verified by the verifier and with the given scopes.
  /// The authorized subject is the `sub` of the token. A request is refused as section 3 of RFC 6750 says: with 401
  /// and the challenge `Bearer` if it has no bearer token, with 401 and the error `invalid_token` if the token is
  /// invalid or expired, and with 403 and the error `insufficient_scope` if the token lacks one of the scopes.
  static <T> BiFunction<HttpRequestMessage, T, Authorization> bearerToken(AccessTokenVerifier verifier, String... requiredScopes) {
    return (request, _) -> {
      String authorization = request.headerValue("Authorization");
      if (authorization == null)
        return new Refused(401, "Bearer", "Authorization header missing");
      if (!authorization.regionMatches(true, 0, "Bearer ", 0, 7))
        return new Refused(401, "Bearer", "Authorization token is not of type Bearer");
      var token = verifier.verify(authorization.substring(7).trim());
      if (token.isEmpty())
        return bearerError(401, "invalid_token", "Bearer token is invalid or expired", "");
      for (String requiredScope : requiredScopes) {
        if (!token.get().scopes().contains(requiredScope))
          return bearerError(
              403,
              "insufficient_scope",
              "Bearer token lacks the scope " + requiredScope,
              ", scope=\"" + String.join(" ", requiredScopes) + "\""
          );
      }
      return Authorization.authorized(token.get().subject());
    };
  }

  private static Refused bearerError(int statusCode, String error, String description, String parameters) {
    return new Refused(
        statusCode,
        "Bearer error=\"" + error + "\", error_description=\"" + description + "\"" + parameters,
        description
    );
  }

  enum States implements State {
    Routed,
    Completed,
    Rejected,
    RollingBack,
    RolledBack,
    Dispatched,
    RejectedRollbackFromCompleted,
    RejectedRollbackFromDispatched
  }

  record MessageId(String clientId, String value) {}

  /// A request routed to an inbox request and found valid by it
  ///
  /// @param body the parsed body of the request
  /// @param messageId the id of the request, by which a retried request is recognised, if it has one
  /// @param authorizedSubject the subject the request is authorized for, if the inbox request has an authorizer
  /// @param processSelector the entity of the application the request triggers an event on
  record RoutedRequest<T>(
      HttpRequestMessage request,
      T body,
      MessageId messageId,
      String authorizedSubject,
      EntitySelector processSelector
  ) {}

}
