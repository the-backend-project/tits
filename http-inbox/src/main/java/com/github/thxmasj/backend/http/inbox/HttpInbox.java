package com.github.thxmasj.backend.http.inbox;

import static com.github.thxmasj.backend.core.EntitySelector.CreationMode.AlwaysCreate;
import static com.github.thxmasj.backend.http.inbox.HttpInbox.EntityModels.RequestRouting;

import com.github.thxmasj.backend.core.BasicEventType;
import com.github.thxmasj.backend.core.DataType;
import com.github.thxmasj.backend.core.EntityModel;
import com.github.thxmasj.backend.core.EntitySelector;
import com.github.thxmasj.backend.core.EntitySelector.ById;
import com.github.thxmasj.backend.core.EventReference;
import com.github.thxmasj.backend.core.EventTrigger;
import com.github.thxmasj.backend.core.EventTrigger.EventSpec;
import com.github.thxmasj.backend.core.EventType;
import com.github.thxmasj.backend.core.IdEntityModel;
import com.github.thxmasj.backend.core.State;
import com.github.thxmasj.backend.core.TransitionModelBuilder.TransitionContext;
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

public interface HttpInbox {

  record RouteId(int metaDataRoute, int contentRoute) {}

  EventType<HttpRequestMessage, Void> RouteRequest = BasicEventType.of(
      "Route request",
      UUID.fromString("e55c0077-eddd-4840-b880-2bf0ace4468a"),
      HttpDataType.forRequest(),
      DataType.none()
  );

  EventTrigger<HttpRequestMessage, HttpRequestMessage, ?> TRIGGER = new EventTrigger<>(
      new EventSpec<>(RouteRequest, Function.identity()),
      List.of(_ -> new ById(UUID.randomUUID(), AlwaysCreate)),
      RequestRouting,
      false
  );
  EventType<Tuple3<String, UUID, HttpRequestMessage>, HttpRequestMessage> RejectRequest = BasicEventType.of(
      "Reject request",
      UUID.fromString("46106429-3c44-45cd-be04-5eb341c8f381"),
      DataType.tuple(DataType.string(), DataType.json(UUID.class), HttpDataType.forRequest()),
      HttpDataType.forRequest()
  );
  EventType<Tuple2<HttpRequestMessage, HttpResponseMessage>, HttpRequestMessage> RejectDuplicateRequest = BasicEventType.of(
      "Reject duplicate request",
      UUID.fromString("304f5036-4ae6-406a-9512-f217e34e542b"),
      DataType.tuple(HttpDataType.forRequest(), HttpDataType.forResponse()),
      HttpDataType.forRequest()
  );
  EventType<Tuple2<RoutedRequest<?>, EventReference>, Tuple2<HttpRequestMessage, EventReference>> AcceptRequest = BasicEventType.of(
      "Accept request",
      UUID.fromString("9615c3fb-4f15-47f5-b5d3-6149a6164d70"),
      DataType.unknown(),
      DataType.tuple(HttpDataType.forRequest(), DataType.eventReference())
  );
  EventType<Tuple3<String, RoutedRequest<?>, EventReference>, HttpRequestMessage> AcceptRollbackRequest = BasicEventType.of(
      "Accept rollback request",
      UUID.fromString("abd02d03-8bdc-4bea-9135-214f31489965"),
      DataType.unknown(),
      HttpDataType.forRequest()
  );
  ResponseEventType<Tuple2<String, EventReference>> CompleteRequest = new ResponseEventType<>(
      "Complete request",
      UUID.fromString("fa608cc7-9a2b-42ec-ab83-5207ab3978a0"),
      DataType.tuple(DataType.string(), DataType.eventReference())
  );
  ResponseEventType<Tuple2<String, EventReference>> CompleteRollbackRequest = new ResponseEventType<>(
      "Complete rollback request",
      UUID.fromString("57fd7951-b922-42ff-99ed-6752f1d23024"),
      DataType.tuple(DataType.string(), DataType.eventReference())
  );
  EventType<HttpResponseMessage, HttpResponseMessage> CompleteDuplicatedRequest = BasicEventType.of(
      "Complete duplicated request",
      UUID.fromString("c448eb33-b5de-4201-9d58-55b9767727b6"),
      HttpDataType.forResponse()
  );
  ResponseEventType<Tuple2<String, EventReference>> CompleteInvalidRequest = new ResponseEventType<>(
      "Complete invalid request",
      UUID.fromString("6bc64696-3b9e-4897-b9d0-de3abf567685"),
      DataType.tuple(DataType.string(), DataType.eventReference())
  );
  ResponseEventType<Tuple2<String, EventReference>> CompleteFailedRequest = new ResponseEventType<>(
      "Complete failed request",
      UUID.fromString("4ea0817f-c153-4fbb-a09a-ffcd5e8d6960"),
      DataType.tuple(DataType.string(), DataType.eventReference())
  );
  EventType<HttpResponseMessage, HttpResponseMessage> CompleteRejectedRequest = BasicEventType.of(
      "Complete rejected request",
      UUID.fromString("887a0173-1847-4e5d-b529-3c25e1f8a5d1"),
      HttpDataType.forResponse(),
      HttpDataType.forResponse()
  );
  EventType<String, Void> RejectAsUnroutable = BasicEventType.of(
      "RejectAsUnroutable",
      UUID.fromString("9b6a57f8-8d3b-46b4-aeef-d8864f19fe13"),
      DataType.string(),
      DataType.none()
  );
  EventType<Refused, Void> RejectAsUnauthorized = BasicEventType.of(
      "RejectAsUnauthorized",
      UUID.fromString("3a204b0b-2740-4b61-bdfa-4149c819be04"),
      DataType.json(Refused.class),
      DataType.none()
  );
  EventType<Refused, HttpResponseMessage> RespondRefused = BasicEventType.of(
      "RespondRefused",
      UUID.fromString("4cdff19a-ab84-4888-88e6-de7c4bed5907"),
      DataType.json(Refused.class),
      HttpDataType.forResponse()
  );
  EventType<HttpResponseMessage, HttpResponseMessage> Respond = BasicEventType.of(
      "Respond",
      UUID.fromString("2387a6a5-a483-428d-839f-26b5fe485247"),
      HttpDataType.forResponse(),
      HttpDataType.forResponse()
  );
  EventType<String, HttpResponseMessage> RespondBadRequest = BasicEventType.of(
      "RespondBadRequest",
      UUID.fromString("a8225270-6a22-4f1f-ae17-5b5519d46df1"),
      DataType.string(),
      HttpDataType.forResponse()
  );
  EventType<MessageId, MessageId> MessageId = BasicEventType.of(
      "MessageId",
      UUID.fromString("f721bf54-891b-41a0-aa63-5d3cbde52437"),
      new MessageIdDataType()
  );
  EventType<MessageId, MessageId> RollbackMessageId = BasicEventType.of(
      "RollbackMessageId",
      UUID.fromString("58cafda3-1818-4015-8f97-3d9f3bd2856f"),
      new MessageIdDataType()
  );
  /// Ids of the requests, by the entity id of their RequestDispatching
  IdEntityModel MessageIds = IdEntityModel.of(
      "MessageIds",
      UUID.fromString("3c8e1f5a-7b2d-4e9c-a6f0-8d4b2e7c1a95"),
      MessageId,
      RollbackMessageId
  );

  static <T> Function<HttpRequestMessage, Validated<T>> jsonParser(Class<T> contentType) {
    return new JsonRequestValidator<>(contentType, true);
  }

  static <T> Function<HttpRequestMessage, Validated<T>> jsonParser(Class<T> contentType, boolean validate) {
    return new JsonRequestValidator<>(contentType, validate);
  }

  /// An authorizer for routes requiring a bearer token, RFC 6750, verified by the verifier and with the given scopes.
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

  enum EntityModels implements EntityModel {
        RequestDispatching {
            private final static UUID id = UUID.fromString("9711bee2-b42b-45ba-8d5e-7f891995a9c9");

            @Override
            public UUID id() {return id;}
        },
        RequestRouting {
            private final static UUID id = UUID.fromString("20755705-fc81-4228-a1a7-5e13d6e3c153");

            @Override
            public UUID id() {return id;}

        }
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

  record RoutedRequest<T>(
      RouteId routeId,
      HttpRequestMessage request,
      T body,
      MessageId messageId,
      String authorizedSubject,
      EntitySelector processSelector
  ) {}

  record CustomResponse<I>(
      ResponseEventType<I> event,
      Function<TransitionContext<I>, HttpResponseMessage> messageCreator
  ) {}
}
