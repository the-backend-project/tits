package com.github.thxmasj.backend.http.inbox;

import com.github.thxmasj.backend.core.BasicEventType;
import com.github.thxmasj.backend.core.DataType;
import com.github.thxmasj.backend.core.EntityModel;
import com.github.thxmasj.backend.core.EntityModelGroup;
import com.github.thxmasj.backend.core.EntitySelector;
import com.github.thxmasj.backend.core.EventReference;
import com.github.thxmasj.backend.core.EventTrigger;
import com.github.thxmasj.backend.core.EventType;
import com.github.thxmasj.backend.core.IdEntityModel;
import com.github.thxmasj.backend.core.IndexEntityModel;
import com.github.thxmasj.backend.core.State;
import com.github.thxmasj.backend.core.Tuples.Tuple2;
import com.github.thxmasj.backend.core.Tuples.Tuple3;
import com.github.thxmasj.backend.core.Validated;
import com.github.thxmasj.backend.core.http.HttpDataType;
import com.github.thxmasj.backend.core.message.http.HttpRequestMessage;
import com.github.thxmasj.backend.core.message.http.HttpResponseMessage;
import com.github.thxmasj.backend.http.inbox.Authorization.Refused;
import java.util.List;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;

/// The HTTP inbox of an application: the [HttpInboxRequest]s it receives, and its `RequestRouting` entity model routing
/// a received request to the inbox request matching it. The inbox is created from the requests with [#of(List)], and
/// gives the entity models to create the state machine with. The state machine runs them with the entity models of the
/// application the requests trigger events on, and so on:
///
/// ```java
/// HttpInbox inbox = HttpInbox.of(List.of(PlaceOrder));
/// StateMachine stateMachine = new StateMachine(inbox.entityModels(), dataSource, schemaName, true);
/// DisposableServer server = new HttpInboxListener(stateMachine, inbox).listen(port);
/// ```
public interface HttpInbox {

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
  /// A valid request of an inbox request with `deduplicateOn`, whose message id is claimed before it is dispatched. It
  /// is never stored, as one of the outcomes of the claim always is.
  EventType<RoutedRequest<?>, Void> RequestValidated = BasicEventType.of(
      "Request validated",
      UUID.fromString("d4f5f8e7-ab7a-4f20-ac7a-dda94d790c1f"),
      DataType.unknown(),
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

  /// The entity of the application an accepted request triggered its event on, indexed by the entity id of the
  /// request, so that the requests of an entity are found by its entity id, like the outbox requests of an entity
  EventType<UUID, UUID> ProcessReferenced = IndexEntityModel.referenceEventType(
      "Process referenced",
      UUID.fromString("b8d2f4e6-3a1c-4e7b-9f05-6c8a2d4e1b39")
  );
  /// The entities of the application the requests triggered their events on, by the entity id of the request
  IndexEntityModel Index = IndexEntityModel.of(
      "RequestIndex",
      UUID.fromString("e1a7c3d9-5b2f-4a86-8c4e-0d9f6b3a7e52"),
      ProcessReferenced
  );

  /// The inbox of the requests.
  ///
  /// Every inbox request is checked to take the responses its own processes may give. From the event of each process,
  /// the transitions taken on it in any state, with all the transitions they may choose, and the transitions of the
  /// events they trigger at once on other entities, in the same session, are followed. The event types they trigger on
  /// [#Request] must be [#RequestCompleted], [#InvalidRequestCompleted], [#FailedRequestCompleted] or one the request
  /// has a `response` for. Delayed and conditional triggers and actions, e.g. outbox requests, are not followed, as
  /// what they lead to is calculated later, so a response given that way is checked only when it is given.
  ///
  /// @throws IllegalArgumentException if an inbox request doesn't take a response its processes may give
  static HttpInbox of(List<HttpInboxRequest<?>> requests) {
    return InboxModels.of(requests);
  }

  /// The entity model routing every request received to the inbox request matching it, and answering it with the
  /// response the inbox request gives it. A request matching no inbox request is answered with 400 Bad Request.
  EntityModel requestRouting();

  /// The requests the inbox receives
  List<HttpInboxRequest<?>> requests();

  /// The entity models of the inbox, [#requestRouting()], the inbox requests, [#MessageIds] and [#Index], to create the
  /// state machine with. It also runs the entity models of the application the inbox requests trigger events on.
  List<EntityModel> entityModels();

  /// Triggers a received request on a new entity of [#requestRouting()]
  EventTrigger<HttpRequestMessage, HttpRequestMessage, ?> trigger();

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
