package com.github.thxmasj.statemachine;

import static com.github.thxmasj.statemachine.BuiltinEntities.InboxExchange.InvalidRequest;
import static com.github.thxmasj.statemachine.BuiltinEntities.InboxExchange.State.Begin;
import static com.github.thxmasj.statemachine.BuiltinEntities.InboxExchange.State.Requested;
import static com.github.thxmasj.statemachine.BuiltinEntities.InboxExchange.State.Responded;
import static com.github.thxmasj.statemachine.BuiltinEntities.InboxExchange.State.RolledBack;
import static com.github.thxmasj.statemachine.BuiltinEntities.InboxExchange.State.RollingBack;
import static com.github.thxmasj.statemachine.BuiltinEntities.States.Dispatched;
import static com.github.thxmasj.statemachine.BuiltinEventTypes.Rollback;
import static com.github.thxmasj.statemachine.EntitySelector.entityId;
import static com.github.thxmasj.statemachine.EntitySelector.entityIdFromSession;
import static com.github.thxmasj.statemachine.TransitionModelBuilder.WithEvent.onEvent;
import static com.github.thxmasj.statemachine.Tuples.tuple;

import com.fasterxml.jackson.core.type.TypeReference;
import com.github.thxmasj.statemachine.BasicEventType.Rollback.Data;
import com.github.thxmasj.statemachine.BuiltinEntities.InboxExchange.MessageId;
import com.github.thxmasj.statemachine.EventType.DataType;
import com.github.thxmasj.statemachine.TransitionModelBuilder.TransitionModel;
import com.github.thxmasj.statemachine.TransitionModelBuilder.WithFilter.Alternative;
import com.github.thxmasj.statemachine.Tuples.Tuple2;
import com.github.thxmasj.statemachine.Tuples.Tuple3;
import com.github.thxmasj.statemachine.database.mssql.SchemaNames.Column;
import com.github.thxmasj.statemachine.database.mssql.SchemaNames.SecondaryIdModel;
import com.github.thxmasj.statemachine.http.ParsedAuthorizationClaims;
import com.github.thxmasj.statemachine.message.http.BadRequest;
import com.github.thxmasj.statemachine.message.http.Created;
import com.github.thxmasj.statemachine.message.http.HttpRequestMessage;
import com.github.thxmasj.statemachine.message.http.HttpResponseCreator;
import com.github.thxmasj.statemachine.message.http.HttpResponseMessage;
import com.github.thxmasj.statemachine.message.http.SeeOther;
import com.github.thxmasj.statemachine.message.http.UnprocessableEntity;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class BuiltinEntities {

  record Request<T>(
      MessageId messageId,
      HttpRequestMessage request,
      T body,
      ParsedAuthorizationClaims.Valid authorizationClaims
  ) {}

  public enum States implements State {Begin, Processing, Completed, Rejected, RolledBack, Dispatched}
  public enum Models implements EntityModel {
    RequestDispatching {
      private final static UUID id = UUID.fromString("9711bee2-b42b-45ba-8d5e-7f891995a9c9");
      @Override public UUID id() {return id;}
      @Override public State initialState() {return States.Begin;}
    },
    RequestHandling {
      private final static UUID id = UUID.fromString("20755705-fc81-4228-a1a7-5e13d6e3c153");
      @Override public UUID id() {return id;}
      @Override public State initialState() {return States.Begin;}
    }
  }

  public EventType<HttpRequestMessage, Void> RouteRequest = BasicEventType.of("Route request", UUID.fromString("e55c0077-eddd-4840-b880-2bf0ace4468a"), HttpRequestMessage.class, Void.class);
  public EventType<HttpRequestMessage, HttpRequestMessage> RejectUnroutableRequest = BasicEventType.of("RejectUnroutableRequest", UUID.fromString("9b6a57f8-8d3b-46b4-aeef-d8864f19fe13"), HttpRequestMessage.class);
  public Map<State, List<TransitionModel<?, ?>>> dispatcherTransitions(Map<Predicate<HttpRequestMessage>, Alternative<HttpRequestMessage, ?, ?>> routes) {
    return Map.of(
        Begin, List.of(
            onEvent(RouteRequest).to(Dispatched)
                .assembleInput()
                .when(routes)
                .when(_ -> true).then(
                    onEvent(RejectUnroutableRequest).to(States.Rejected)
                            .trigger()
                    invalidRequest(),
                    m -> "Request not mapped: " + m.requestLine()
                )
                .output(d -> d)
        )
    );
  }

  public abstract static class InboxExchange implements EntityModel {

    @Override
    public int hashCode() {
      return id().hashCode();
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof InboxExchange ie && ie.id().equals(id());
    }

    public enum State implements com.github.thxmasj.statemachine.State {
      Begin,
      Requested,
      Responded,
      RollingBack,
      RolledBack
    }

    public record MessageId(String clientId, String value) {}

    public static SecondaryIdModel<MessageId> MessageId = new SecondaryIdModel<>() {
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

    public record EventReference(EntityId entityId, int eventNumber) {}

    public static SecondaryIdModel<EventReference> EventReference = new SecondaryIdModel<>() {
      @Override
      public String name() {return "EventReference";}

      @Override
      public List<Column> columns() {
        return List.of(
            new Column("Entity", "UNIQUEIDENTIFIER", id -> ((EventReference) id).entityId().value()),
            new Column("EventNumber", "SMALLINT", id -> ((EventReference) id).eventNumber())
        );
      }

      @Override
      public SecondaryId<EventReference> map(ResultSet resultSet) {
        try {
          return new SecondaryId<>(
              this,
              new EventReference(
                  new EntityId.UUID(UUID.fromString(resultSet.getString("Entity"))),
                  resultSet.getInt("EventNumber")
              )
          );
        } catch (SQLException e) {
          throw new RuntimeException(e);
        }
      }
    };

    interface HttpRequestMessageEvent extends EventType<HttpRequestMessage, HttpRequestMessage> {

      @Override
      default DataType<HttpRequestMessage> inputDataType() {
        return new DataType<>(HttpRequestMessage.class);
      }

      @Override
      default DataType<HttpRequestMessage> outputDataType() {
        return new DataType<>(HttpRequestMessage.class);
      }

    }

    public record RequestType(String name, UUID id) implements HttpRequestMessageEvent {}

    public static RequestType Request = new RequestType(
        "Request",
        UUID.fromString("bb3d9b74-d5ba-49ae-992d-1934ee7be79f")
    );

    interface HttpResponseMessageEvent extends EventType<HttpResponseMessage, HttpResponseMessage> {

      @Override
      default DataType<HttpResponseMessage> inputDataType() {
        return new DataType<>(HttpResponseMessage.class);
      }

      @Override
      default DataType<HttpResponseMessage> outputDataType() {
        return new DataType<>(HttpResponseMessage.class);
      }

    }

    public record ResponseType(String name, UUID id) implements HttpResponseMessageEvent {}

    public static ResponseType Response = new ResponseType(
        "Response",
        UUID.fromString("f4e745c9-0e52-405e-82b4-e5b43e3b315f")
    );

    public static EventType<String, Void> InvalidRequest = BasicEventType.of(
        "InvalidRequest",
        UUID.fromString("ae58df43-0a96-4749-afb8-33f29f7aa0df"),
        String.class,
        Void.class
    );
    public static EventType<EntityId, Void> AcceptedRequest = BasicEventType.of(
        "AcceptedRequest",
        UUID.fromString("837f24e1-e3d8-48df-b39d-8bd6e4e7f96f"),
        EntityId.class,
        Void.class
    );
    public static EventType<EntityId, Void> AcceptedRollbackRequest = BasicEventType.of(
        "AcceptedRollbackRequest",
        UUID.fromString("6185215d-667c-4146-974d-77ccc6431319"),
        EntityId.class,
        Void.class
    );
    public static EventType<String, Void> SeeOther = BasicEventType.of(
        "SeeOther",
        UUID.fromString("52bbff54-aa60-4fba-a78c-1625dcdda4e2"),
        String.class,
        Void.class
    );
    public static EventType<Tuple3<HttpRequestMessage, MessageId, String>, HttpRequestMessage> RejectedRequest =
        BasicEventType.of(
            "RejectedRequest",
            UUID.fromString("63a1c14d-b0e8-459b-9eb5-fac9035c8450"),
            new DataType<>(new TypeReference<Tuple3<HttpRequestMessage, MessageId, String>>() {}, HttpRequestMessage.class, MessageId.class, String.class),
            HttpRequestMessage.class
        );
    public static EventType<Tuple2<HttpRequestMessage, EventLog>, HttpRequestMessage> RejectedDuplicatedRequest =
        BasicEventType.of(
            "RejectedDuplicatedRequest",
            UUID.fromString("26013ed4-f986-4b3f-ba63-1e168f88daf1"),
            new DataType<Tuple2<HttpRequestMessage, EventLog>>(
                new TypeReference<Tuple2<HttpRequestMessage, EventLog>>() {},
                HttpRequestMessage.class,
                EventLog.class
            ),
            new DataType<>(HttpRequestMessage.class)
        );
    public static EventType<Tuple2<HttpRequestMessage, EventLog>, HttpRequestMessage> DuplicatedRequest =
        BasicEventType.of(
            "DuplicatedRequest",
            UUID.fromString("85db8899-cc28-4199-8b37-03b683d4c823"),
            new DataType<Tuple2<HttpRequestMessage, EventLog>>(
                new TypeReference<Tuple2<HttpRequestMessage, EventLog>>() {},
                HttpRequestMessage.class,
                EventLog.class
            ),
            new DataType<>(HttpRequestMessage.class)
        );
    public static EventType<HttpRequestMessage, HttpRequestMessage> RollbackRequest = BasicEventType.of(
        "RollbackRequest",
        UUID.fromString("d5364301-87dc-4aaf-a5af-a8dc2b488aba"),
        HttpRequestMessage.class
    );
    public static EventType<HttpResponseMessage, HttpResponseMessage> RollbackResponse = BasicEventType.of(
        "RollbackResponse",
        UUID.fromString("c02d864d-bf25-4a45-98f4-fe579a7eedbf"),
        HttpResponseMessage.class
    );

    @Override
    public String toString() {
      return "InboxExchange";
    }

    @Override
    public String name() {
      return "InboxExchange";
    }

    @Override
    public UUID id() {
      return UUID.fromString("9387d8ca-4ecb-4c85-abc1-5ff432485ae8");
    }


    @Override
    public State initialState() {
      return State.Begin;
    }

    @Override
    public List<SecondaryIdModel<?>> secondaryIds() {
      return List.of(MessageId, EventReference);
    }

    protected abstract Map<Predicate<HttpRequestMessage>, Alternative<HttpRequestMessage, ?, ?>> routes();

    protected abstract List<TransitionModel<?, ?>> responseTransitions();

    protected Predicate<HttpRequestMessage> rollbackPredicate() {
      return _ -> false;
    }

    protected EntityModel rollbackEntity() {
      return null;
    }

    @SafeVarargs
    private static List<TransitionModel<?, ?>> join(
        List<TransitionModel<?, ?>>... lists
    ) {
      var result = new ArrayList<TransitionModel<?, ?>>();
      for (var list : lists) {
        result.addAll(list);
      }
      return result;
    }

    public TransitionModel<Tuple3<HttpRequestMessage, MessageId, String>, HttpRequestMessage> rejectedRequest() {
      return onEvent(RejectedRequest).to(Requested)
          .assemble(d -> d)
          .trigger(Response)
          .with(d -> unprocessableEntity(
              d.input().data().t3(),
              d.log().entityId(),
              d.correlationId(),
              d.timestamp()
          ))
          .on(this)
          .identifiedBy(entityIdFromSession())
          .newIdentifier(MessageId, d -> d.t1().input().data().t2())
          .output(d -> d.t1().t1().input().data().t1());
    }

    public TransitionModel<Tuple2<HttpRequestMessage, EventLog>, HttpRequestMessage> invalidDuplicatedRequest(String reason) {
      return onEvent(RejectedDuplicatedRequest).to(Requested)
          .assemble(d -> d)
          .trigger(Response)
          .with(d -> badRequest(reason, d.log().entityId(), d.correlationId(), d.timestamp()))
          .on(this)
          .identifiedBy(entityIdFromSession())
          .output(d -> d.t1().input().data().t1());
    }

    public TransitionModel<Tuple2<HttpRequestMessage, EventLog>, HttpRequestMessage> duplicatedRequest() {
      return onEvent(DuplicatedRequest).to(Requested)
          .assembleInput()
          // Trigger Response with data from original Response
          .trigger(Response).with(d -> d.t2().one(Response)).on(this).identifiedBy(entityIdFromSession())
          .output(d -> d.t1().t1());
    }

    protected TransitionModel<HttpRequestMessage, HttpRequestMessage> initialRollbackRequest(Function<HttpRequestMessage, MessageId> messageIdCreator) {
      return onEvent(RollbackRequest).to(RollingBack)
          .assemble((input, log) -> tuple(input.data(), log.entityId()))
          .newIdentifier(MessageId, d -> messageIdCreator.apply(d.t1()))
          .trigger(AcceptedRollbackRequest).with(d -> d.t1().t2()).on(this).identifiedBy(entityIdFromSession())
          .output(d -> d.t1().t1().t1());
    }

    protected TransitionModel<HttpRequestMessage, HttpRequestMessage> rollbackRequestOn(EntityModel on) {
      return onEvent(RollbackRequest).to(RollingBack)
          .assemble((input, log) -> tuple(input.data(), log.entityId(), log.id(EventReference)))
          .trigger(Rollback)
          .with(d -> new Data(d.t3().eventNumber() - 1, "Inbox"))
          .on(on)
          .identifiedBy(entityId(d -> d.t3().entityId().value()))
          .trigger(AcceptedRollbackRequest)
          .with(d -> d.t1().t2())
          .on(this)
          .identifiedBy(entityIdFromSession())
          .output(d -> d.t1().t1().t1());
    }

    public static String from(String line, String pattern, int captureGroup) {
      Matcher matcher = Pattern.compile(pattern).matcher(line);
      return matcher.find() ? matcher.group(captureGroup) : null;
    }

    public Map<com.github.thxmasj.statemachine.State, List<TransitionModel<?, ?>>> transitions() {
      return Map.of(
          Begin, List.of(
              onEvent(Request).to(Requested)
                  //.whenReject(rejectedRequest())
                  .whenDuplicate(
                      MessageId,
                      (input, log) -> input.message().equals(log.one(HttpRequestMessage.class).message()),
                      duplicatedRequest()
                  )
                  .whenDuplicate(MessageId, (_, log) -> log.oneIfExists(RollbackResponse).isPresent(), invalidDuplicatedRequest("Rolled back"))
                  .whenDuplicate(MessageId, (_, _) -> true, invalidDuplicatedRequest("Conflict"))
                  .assembleInput()
                  .when(routes())
                  .when(_ -> true).then(invalidRequest(), m -> "Request not mapped: " + m.requestLine())
                  .output(d -> d),
              onEvent(RollbackRequest).to(RollingBack)
                  .assemble((input, log) -> tuple(input.data(), log.entityId()))
                  // Identifier should be created by trigger (CreateIfNotExists)
                  //.newIdentifier(MessageId, d -> messageIdCreator.apply(d.t1()))
                  .trigger(AcceptedRollbackRequest).with(d -> d.t2()).on(this).identifiedBy(entityIdFromSession())
                  .output(d -> d.t1().t1())
          ),
          Requested, join(
              List.of(
                  onEvent(Response).to(Responded).assembleInput().output(d -> d),
                  onEvent(AcceptedRequest).to(Responded)
                      .assemble(c -> tuple(c.input().data(), c.timestamp(), c.correlationId()))
                      .when(_ -> true).then(
                          onEvent(Response).to(Responded).assembleInput().output(d -> d),
                          d -> createResponseMessage(new Created(), d.t1(), d.t3(), d.t2(), "Created")
                      ).output(),
                  onResponseEvent(InvalidRequest, new BadRequest()),
                  onResponseEvent(SeeOther, new SeeOther())
              ),
              responseTransitions()
          ),
          Responded, List.of(
              onEvent(RollbackRequest).to(RollingBack)
                  .assemble((input, log) -> tuple(input.data(), log.entityId(), log.id(EventReference)))
                  .trigger(Rollback)
                  .with(d -> new Data(d.t3().eventNumber() - 1, "Inbox"))
                  .on(rollbackEntity())
                  .identifiedBy(entityId(d -> d.t3().entityId().value()))
                  .trigger(AcceptedRollbackRequest)
                  .with(d -> d.t1().t2())
                  .on(this)
                  .identifiedBy(entityIdFromSession())
                  .output(d -> d.t1().t1().t1())

//              onEvent(Request).to(RollingBack)
//                  .assembleInput()
//                  .when(rollbackPredicate()).then(rollbackRequestOn(rollbackEntity()))
//                  .when(_ -> true).then(invalidRequest(), _ -> "Request not mapped")
//                  .output(d -> d)
          ),
          RollingBack, List.of(
              onEvent(RollbackResponse).to(RolledBack).assembleInput().output(d -> d),
              onEvent(AcceptedRollbackRequest).to(RolledBack)
                  .assemble(c -> c)
                  .when(_ -> true).then(
                      onEvent(RollbackResponse).to(RolledBack).assembleInput().output(d -> d),
                      d -> createResponseMessage(
                          new Created(),
                          d.log().entityId(),
                          d.correlationId(),
                          d.timestamp(),
                          "Rolled back"
                      )
                  ).output()
          ),
          RolledBack, List.of()
      );
    }

  }

  protected static TransitionModel<String, Void> invalidRequest() {
    return onResponseEvent(InvalidRequest, new BadRequest());
  }

  protected static TransitionModel<String, Void> onResponseEvent(
      EventType<String, Void> eventType,
      HttpResponseCreator responseCreator
  ) {
    return onEvent(eventType).to(Responded)
        .assemble(c -> tuple(c.input().data(), c.log().entityId(), c.timestamp(), c.correlationId()))
        .when(_ -> true).then(
            onEvent(InboxExchange.Response).to(Responded).assembleInput().output(d -> d),
            d -> createResponseMessage(responseCreator, d.t2(), d.t4(), d.t3(), d.t1())
        ).output();
  }

  private static HttpResponseMessage badRequest(
      String detail,
      EntityId entityId,
      String correlationId,
      ZonedDateTime timestamp
  ) {
    return createResponseMessage(new BadRequest(), entityId, correlationId, timestamp, detail);
  }

  private static HttpResponseMessage unprocessableEntity(
      String detail,
      EntityId entityId,
      String correlationId,
      ZonedDateTime timestamp
  ) {
    return createResponseMessage(new UnprocessableEntity(), entityId, correlationId, timestamp, detail);
  }

  public static HttpResponseMessage createResponseMessage(
      HttpResponseCreator responseCreator,
      EntityId entityId,
      String correlationId,
      ZonedDateTime timestamp,
      String data
  ) {
    return responseCreator.create(
        data, new OutgoingRequestCreator.Context() {
          @Override
          public EntityId entityId() {
            return entityId;
          }

          @Override
          public String correlationId() {
            return correlationId;
          }

          @Override
          public ZonedDateTime timestamp() {
            return timestamp;
          }
        }
    );
  }

}
