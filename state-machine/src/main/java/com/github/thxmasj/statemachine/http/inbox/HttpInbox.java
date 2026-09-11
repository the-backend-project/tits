package com.github.thxmasj.statemachine.http.inbox;

import com.fasterxml.jackson.core.type.TypeReference;
import com.github.thxmasj.statemachine.BasicEventType;
import com.github.thxmasj.statemachine.EntityId;
import com.github.thxmasj.statemachine.EntityModel;
import com.github.thxmasj.statemachine.EntitySelector;
import com.github.thxmasj.statemachine.EntitySelector.ById;
import com.github.thxmasj.statemachine.EventReference;
import com.github.thxmasj.statemachine.EventTrigger;
import com.github.thxmasj.statemachine.EventTrigger.EventSpec;
import com.github.thxmasj.statemachine.EventType;
import com.github.thxmasj.statemachine.EventType.DataType;
import com.github.thxmasj.statemachine.SecondaryId;
import com.github.thxmasj.statemachine.State;
import com.github.thxmasj.statemachine.TransitionModelBuilder.TransitionContext;
import com.github.thxmasj.statemachine.Tuples.Tuple2;
import com.github.thxmasj.statemachine.Tuples.Tuple3;
import com.github.thxmasj.statemachine.Validated;
import com.github.thxmasj.statemachine.database.mssql.SchemaNames.Column;
import com.github.thxmasj.statemachine.database.mssql.SchemaNames.SecondaryIdModel;
import com.github.thxmasj.statemachine.message.http.HttpRequestMessage;
import com.github.thxmasj.statemachine.message.http.HttpResponseMessage;
import com.nimbusds.jwt.SignedJWT;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.ParseException;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

import static com.github.thxmasj.statemachine.EntitySelector.CreationMode.AlwaysCreate;
import static com.github.thxmasj.statemachine.Validated.invalid;
import static com.github.thxmasj.statemachine.Validated.valid;
import static com.github.thxmasj.statemachine.http.inbox.HttpInbox.EntityModels.RequestRouting;
import static com.github.thxmasj.statemachine.http.inbox.HttpInbox.States.Begin;

public interface HttpInbox {

  record RouteId(int metaDataRoute, int contentRoute) {}

  EventType<HttpRequestMessage, Void> RouteRequest = BasicEventType.of("Route request", UUID.fromString("e55c0077-eddd-4840-b880-2bf0ace4468a"), HttpRequestMessage.class, Void.class);

  EventTrigger<HttpRequestMessage, HttpRequestMessage, ?> TRIGGER = new EventTrigger<>(
      new EventSpec<>(RouteRequest, Function.identity()),
      List.of(_ -> new ById(new EntityId.UUID(UUID.randomUUID()), AlwaysCreate)),
      RequestRouting,
      false
  );
  EventType<Tuple3<String, EntityId, HttpRequestMessage>, HttpRequestMessage> RejectRequest = BasicEventType.of(
      "Reject request",
      UUID.fromString("46106429-3c44-45cd-be04-5eb341c8f381"),
      new DataType<>(new TypeReference<>() {}, String.class, EntityId.class, HttpRequestMessage.class),
      HttpRequestMessage.class
  );
  EventType<Tuple2<HttpRequestMessage, HttpResponseMessage>, HttpRequestMessage> RejectDuplicateRequest = BasicEventType.of(
      "Reject duplicate request",
      UUID.fromString("304f5036-4ae6-406a-9512-f217e34e542b"),
      new DataType<>(new TypeReference<>() {}, HttpRequestMessage.class, HttpResponseMessage.class),
      HttpRequestMessage.class
  );
  EventType<Tuple2<RoutedRequest<?>, EventReference>, Tuple2<HttpRequestMessage, EventReference>> AcceptRequest = BasicEventType.of(
      "Accept request",
      UUID.fromString("9615c3fb-4f15-47f5-b5d3-6149a6164d70"),
      new DataType<>(new TypeReference<>() {}, RoutedRequest.class, EventReference.class),
      new DataType<>(new TypeReference<>() {}, HttpRequestMessage.class, EventReference.class)
  );
  EventType<Tuple3<String, RoutedRequest<?>, EventReference>, HttpRequestMessage> AcceptRollbackRequest = BasicEventType.of(
      "Accept rollback request",
      UUID.fromString("abd02d03-8bdc-4bea-9135-214f31489965"),
      new DataType<>(new TypeReference<>() {}, String.class, RoutedRequest.class, EventReference.class),
      HttpRequestMessage.class
  );
  ResponseEventType<Tuple2<String, EventReference>> CompleteRequest = new ResponseEventType<>(
      "Complete request",
      UUID.fromString("fa608cc7-9a2b-42ec-ab83-5207ab3978a0"),
      new DataType<>(new TypeReference<>() {}, String.class, EventReference.class)
  );
  ResponseEventType<Tuple2<String, EventReference>> CompleteRollbackRequest = new ResponseEventType<>(
      "Complete rollback request",
      UUID.fromString("57fd7951-b922-42ff-99ed-6752f1d23024"),
      new DataType<>(new TypeReference<>() {}, String.class, EntityId.class)
  );
  EventType<HttpResponseMessage, HttpResponseMessage> CompleteDuplicatedRequest = BasicEventType.of(
      "Complete duplicated request",
      UUID.fromString("c448eb33-b5de-4201-9d58-55b9767727b6"),
      HttpResponseMessage.class,
      HttpResponseMessage.class
  );
  ResponseEventType<Tuple2<String, EventReference>> CompleteInvalidRequest = new ResponseEventType<>(
      "Complete invalid request",
      UUID.fromString("6bc64696-3b9e-4897-b9d0-de3abf567685"),
      new DataType<>(new TypeReference<>() {}, String.class, EventReference.class)
  );
  EventType<HttpResponseMessage, HttpResponseMessage> CompleteRejectedRequest = BasicEventType.of(
      "Complete rejected request",
      UUID.fromString("887a0173-1847-4e5d-b529-3c25e1f8a5d1"),
      HttpResponseMessage.class,
      HttpResponseMessage.class
  );
  EventType<String, Void> RejectAsUnroutable = BasicEventType.of("RejectAsUnroutable", UUID.fromString("9b6a57f8-8d3b-46b4-aeef-d8864f19fe13"), String.class, Void.class);
  EventType<HttpResponseMessage, HttpResponseMessage> Respond = BasicEventType.of(
      "Respond",
      UUID.fromString("2387a6a5-a483-428d-839f-26b5fe485247"),
      HttpResponseMessage.class,
      HttpResponseMessage.class
  );
  EventType<String, HttpResponseMessage> RespondBadRequest = BasicEventType.of("RespondBadRequest", UUID.fromString("a8225270-6a22-4f1f-ae17-5b5519d46df1"), String.class, HttpResponseMessage.class);
  SecondaryIdModel<MessageId> MessageId = new SecondaryIdModel<>() {
    @Override
    public UUID id() {
      return UUID.fromString("f721bf54-891b-41a0-aa63-5d3cbde52437");
    }

    @Override
    public String name() {return "MessageId";}

    @Override
    public List<Column> columns() {
      return List.of(
          new Column("ClientId", "VARCHAR(100)", id -> ((MessageId) id).clientId()),
          new Column("Value", "VARCHAR(100)", id -> ((MessageId) id).value())
      );
    }

    @Override
    public SecondaryId<MessageId> map(ResultSet resultSet) {
      try {
        return new SecondaryId<>(this, new MessageId(resultSet.getString("ClientId"), resultSet.getString("Value")));
      } catch (SQLException e) {
        throw new RuntimeException(e);
      }
    }
  };
  SecondaryIdModel<MessageId> RollbackMessageId = new SecondaryIdModel<>() {
    @Override
    public UUID id() {
      return UUID.fromString("58cafda3-1818-4015-8f97-3d9f3bd2856f");
    }

    @Override
    public String name() {return "RollbackMessageId";}

    @Override
    public List<Column> columns() {
      return List.of(
          new Column("ClientId", "VARCHAR(100)", id -> ((MessageId) id).clientId()),
          new Column("Value", "VARCHAR(100)", id -> ((MessageId) id).value())
      );
    }

    @Override
    public SecondaryId<MessageId> map(ResultSet resultSet) {
      try {
        return new SecondaryId<>(this, new MessageId(resultSet.getString("ClientId"), resultSet.getString("Value")));
      } catch (SQLException e) {
        throw new RuntimeException(e);
      }
    }
  };

  static <T> Function<HttpRequestMessage, Validated<T>> jsonParser(Class<T> contentType) {
    return new JsonRequestValidator<>(contentType, true);
  }

  static <T> Function<HttpRequestMessage, Validated<T>> jsonParser(Class<T> contentType, boolean validate) {
    return new JsonRequestValidator<>(contentType, validate);
  }

  static Validated<String> authorize(HttpRequestMessage httpRequest) {
    String v = httpRequest.headerValue("Authorization");
    if (v == null)
      return invalid("Authorization header missing");
    if (!v.startsWith("Bearer "))
      return invalid("Authorization token is not of type Bearer");
    String token = v.substring("Bearer ".length());
    String subject;
    try {
      SignedJWT jws = SignedJWT.parse(token);
      subject = jws.getJWTClaimsSet().getSubject();
    } catch (ParseException e) {
      return invalid("Bearer token is invalid");
    }
    return valid(subject);
  }

  enum EntityModels implements EntityModel {
        RequestDispatching {
            private final static UUID id = UUID.fromString("9711bee2-b42b-45ba-8d5e-7f891995a9c9");

            @Override
            public UUID id() {return id;}

            @Override
            public State initialState() {return Begin;}

            @Override
            public List<SecondaryIdModel<?>> secondaryIds() {
                return List.of(
                        MessageId,
                        RollbackMessageId
                );
            }
        },
        RequestRouting {
            private final static UUID id = UUID.fromString("20755705-fc81-4228-a1a7-5e13d6e3c153");

            @Override
            public UUID id() {return id;}

            @Override
            public State initialState() {return Begin;}
        }
    }

    enum States implements State {
        Begin,
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
