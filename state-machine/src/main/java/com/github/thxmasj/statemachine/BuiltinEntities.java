package com.github.thxmasj.statemachine;

import static com.github.thxmasj.statemachine.BuiltinEntities.InboxExchange.State.Begin;
import static com.github.thxmasj.statemachine.BuiltinEntities.InboxExchange.State.Dispatched;
import static com.github.thxmasj.statemachine.BuiltinEntities.InboxExchange.State.Responded;
import static com.github.thxmasj.statemachine.BuiltinEntities.InboxExchange.State.RolledBack;
import static com.github.thxmasj.statemachine.BuiltinEntities.InboxExchange.State.RollingBack;
import static com.github.thxmasj.statemachine.BuiltinEventTypes.Rollback;
import static com.github.thxmasj.statemachine.EntitySelector.entityId;
import static com.github.thxmasj.statemachine.EntitySelector.entityIdFromSession;
import static com.github.thxmasj.statemachine.TransitionModelBuilder.WithEvent.onEvent;
import static com.github.thxmasj.statemachine.Tuples.tuple;

import com.github.thxmasj.statemachine.BasicEventType.Rollback.Data;
import com.github.thxmasj.statemachine.TransitionModelBuilder.TransitionModel;
import com.github.thxmasj.statemachine.database.mssql.ChangeState.Change;
import com.github.thxmasj.statemachine.database.mssql.SchemaNames.Column;
import com.github.thxmasj.statemachine.database.mssql.SchemaNames.SecondaryIdModel;
import com.github.thxmasj.statemachine.message.http.BadRequest;
import com.github.thxmasj.statemachine.message.http.Created;
import com.github.thxmasj.statemachine.message.http.HttpRequestMessage;
import com.github.thxmasj.statemachine.message.http.HttpResponseCreator;
import com.github.thxmasj.statemachine.message.http.HttpResponseMessage;
import com.github.thxmasj.statemachine.message.http.SeeOther;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public class BuiltinEntities {

  public abstract static class InboxExchange implements EntityModel {

    @Override
    public int hashCode() {
      return id().hashCode();
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof InboxExchange ie && ie.id().equals(id());
    }

    public enum State implements com.github.thxmasj.statemachine.State {Begin, Dispatched, Responded, RollingBack, RolledBack}

    public record MessageId(String clientId, String value) {}

    public static SecondaryIdModel MessageId = new SecondaryIdModel() {
      @Override
      public String name() {return "MessageId";}

      @Override
      public List<Column> columns() {
        return List.of(
            new Column("ClientId", "VARCHAR(100)", id -> ((MessageId)id).clientId()),
            new Column("Value", "VARCHAR(100)", id -> ((MessageId)id).value())
        );
      }

      @Override
      public SecondaryId map(ResultSet resultSet) {
        try {
          return new SecondaryId(this, new MessageId(resultSet.getString("ClientId"), resultSet.getString("Value")));
        } catch (SQLException e) {
          throw new RuntimeException(e);
        }
      }
    };

    public record EventReference(EntityId entityId, int eventNumber) {}

    public static SecondaryIdModel EventReference = new SecondaryIdModel() {
      @Override
      public String name() {return "EventReference";}

      @Override
      public List<Column> columns() {
        return List.of(
            new Column("Entity", "UNIQUEIDENTIFIER", id -> ((EventReference)id).entityId().value()),
            new Column("EventNumber", "SMALLINT", id -> ((EventReference)id).eventNumber())
        );
      }

      @Override
      public SecondaryId map(ResultSet resultSet) {
        try {
          return new SecondaryId(this, new EventReference(new EntityId.UUID(UUID.fromString(resultSet.getString("Entity"))), resultSet.getInt("EventNumber")));
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

    public static RequestType Request = new RequestType("Request", UUID.fromString("bb3d9b74-d5ba-49ae-992d-1934ee7be79f"));

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

    public static ResponseType Response = new ResponseType("Response", UUID.fromString("f4e745c9-0e52-405e-82b4-e5b43e3b315f"));

    public static EventType<String, Void> InvalidRequest = BasicEventType.of("InvalidRequest", UUID.fromString("ae58df43-0a96-4749-afb8-33f29f7aa0df"), String.class, Void.class);
    public static EventType<String, Void> AcceptedRequest = BasicEventType.of("AcceptedRequest", UUID.fromString("837f24e1-e3d8-48df-b39d-8bd6e4e7f96f"), String.class, Void.class);
    public static EventType<String, Void> AcceptedRollbackRequest = BasicEventType.of("AcceptedRollbackRequest", UUID.fromString("6185215d-667c-4146-974d-77ccc6431319"), String.class, Void.class);
    public static EventType<String, Void> SeeOther = BasicEventType.of("SeeOther", UUID.fromString("52bbff54-aa60-4fba-a78c-1625dcdda4e2"), String.class, Void.class);
    public static EventType<HttpRequestMessage, HttpRequestMessage> RollbackRequest = BasicEventType.of("RollbackRequest", UUID.fromString("d5364301-87dc-4aaf-a5af-a8dc2b488aba"), HttpRequestMessage.class);
    public static EventType<HttpResponseMessage, HttpResponseMessage> RollbackResponse = BasicEventType.of("RollbackResponse", UUID.fromString("c02d864d-bf25-4a45-98f4-fe579a7eedbf"), HttpResponseMessage.class);

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
    public List<SecondaryIdModel> secondaryIds() {
      return List.of(MessageId, EventReference);
    }

    protected abstract List<TransitionModel<?, ?>> requestTransitions();

    protected abstract List<TransitionModel<?, ?>> responseTransitions();

    protected Predicate<HttpRequestMessage> rollbackPredicate() {
      return _ -> false;
    }

    protected EntityModel rollbackEntity() {
      return null;
    }

    private static List<TransitionModel<?, ?>> join(List<TransitionModel<?, ?>> list1, List<TransitionModel<?, ?>> list2) {
      return Stream.concat(list1.stream(), list2.stream()).toList();
    }

    private TransitionModel<?, ?> secondaryIdAlreadyExists() {
      return onEvent(BuiltinEventTypes.SecondaryIdAlreadyExists).to(Responded)
          .assemble(c -> c.input().data())
          // Duplicate request gives the original response
          .when(d -> d.t2().model() == MessageId &&
              d.t1().newEvent().data().equals(d.t3().one(HttpRequestMessage.class).message()))
          .then(onEvent(Response).to(Responded).assembleInput().output(d -> d), d -> d.t3().one(Response))
          // Request after rollback gives the response BadRequest("Rolled back")
          .when(d -> d.t2().model() == MessageId && d.t3().oneIfExists(RollbackResponse).isPresent())
          .then(onEvent(Response).to(Responded).assembleInput().output(d -> d), d -> badRequest("Rolled back", d.t1()))
          // Request using already used message id gives the response BadRequest("Conflict")
          .when(d -> d.t2().model() == MessageId)
          .then(onEvent(Response).to(Responded).assembleInput().output(d -> d), d -> badRequest("Conflict", d.t1()))
          .output();
    }

    protected TransitionModel<HttpRequestMessage, HttpRequestMessage> initialRollbackRequest(Function<HttpRequestMessage, MessageId> messageIdCreator) {
      return onEvent(RollbackRequest).to(RollingBack)
          .assemble((input, log) -> tuple(input.data(), log.entityId()))
          .newIdentifier(MessageId, d -> messageIdCreator.apply(d.t1()))
          .trigger(AcceptedRollbackRequest).with(_ -> "Rolled back").on(this).identifiedBy(entityIdFromSession())
          .output(d -> d.t1().t1());
    }

    protected TransitionModel<HttpRequestMessage, HttpRequestMessage> rollbackRequestOn(EntityModel on) {
      return onEvent(RollbackRequest).to(RollingBack)
          .assemble((input, log) -> tuple(input.data(), log.entityId(), log.id(EventReference)))
          .trigger(Rollback).with(d -> new Data(((EventReference)d.t3().data()).eventNumber(), "Inbox")).on(on).identifiedBy(entityId(d -> ((EventReference)d.t3().data()).entityId().value()))
          .trigger(AcceptedRollbackRequest).with(_ -> "Rolled back").on(this).identifiedBy(entityIdFromSession())
          .output(d -> d.t1().t1().t1());
    }

    protected TransitionModel<String, Void> invalidRequest() {
      return onResponseEvent(InvalidRequest, new BadRequest());
    }

    public static String from(String line, String pattern, int captureGroup) {
      Matcher matcher = Pattern.compile(pattern).matcher(line);
      return matcher.find() ? matcher.group(captureGroup) : null;
    }

    public Map<com.github.thxmasj.statemachine.State, List<TransitionModel<?, ?>>> transitions() {
      return Map.of(
          Begin, join(List.of(secondaryIdAlreadyExists()), requestTransitions()),
          Dispatched, join(
              List.of(
                  onResponseEvent(AcceptedRequest, new Created()),
                  onResponseEvent(InvalidRequest, new BadRequest()),
                  onResponseEvent(SeeOther, new SeeOther())
              ),
              responseTransitions()
          ),
          Responded, List.of(
              onEvent(Request).to(RollingBack)
                  .assembleInput()
                  .when(rollbackPredicate()).then(rollbackRequestOn(rollbackEntity()))
                  .when(_ -> true).then(invalidRequest(), _ -> "Request not mapped")
                  .output(d -> d)
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

    protected static TransitionModel<String, Void> onResponseEvent(EventType<String, Void> eventType, HttpResponseCreator responseCreator) {
      return onEvent(eventType).to(Responded)
          .assemble((input, log) -> tuple(input.data(), log.entityId()))
          .when(_ -> true).then(
              onEvent(Response).to(Responded).assembleInput().output(d -> d),
              d -> createResponseMessage(responseCreator, d.t2(), "N/A", null, d.t1()) // TODO
          ).output();
    }

    private static HttpResponseMessage badRequest(String detail, Change change) {
      return createResponseMessage(new BadRequest(), change.entityId(), change.correlationId(), change.newEvent().timestamp(), detail);
    }

    private static HttpResponseMessage createResponseMessage(
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

}
