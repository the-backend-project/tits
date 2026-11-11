package com.github.thxmasj.backend.core.database.mssql;

import static java.util.stream.Collectors.joining;

import com.github.thxmasj.backend.core.BasicEventType;
import com.github.thxmasj.backend.core.ConditionTrigger;
import com.github.thxmasj.backend.core.DataType;
import com.github.thxmasj.backend.core.DelayedEvent;
import com.github.thxmasj.backend.core.EntityId;
import com.github.thxmasj.backend.core.EntityModel;
import com.github.thxmasj.backend.core.Event;
import com.github.thxmasj.backend.core.IdEntityModel;
import com.github.thxmasj.backend.core.IndexEntityModel;
import com.github.thxmasj.backend.core.StateMachine.ProcessResult;
import com.github.thxmasj.backend.core.TriggerType;
import com.github.thxmasj.backend.core.database.Client;
import com.github.thxmasj.backend.core.database.Client.PrimaryKeyConstraintViolation;
import com.github.thxmasj.backend.core.database.Client.Query.Builder;
import com.github.thxmasj.backend.core.database.Client.UniqueIndexConstraintViolation;
import com.github.thxmasj.backend.core.database.EventAlreadyExists;
import com.github.thxmasj.backend.core.database.IdEventAlreadyExists;
import java.time.Clock;
import java.time.ZonedDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import reactor.core.publisher.Flux;

public class ChangeState {

  private final Client databaseClient;
  private final Clock clock;
  private final String schema;

  public ChangeState(Client databaseClient, String schema, Clock clock) {
    this.databaseClient = databaseClient;
    this.clock = clock;
    this.schema = schema;
  }

  private void bind(Builder spec, ZonedDateTime timestamp, String correlationId, Change change) {
    Event<?> event = change.newEvent();
    if (change.entityId() != null)
      spec.bind("entityId", change.entityId().value());
    spec.bind("entityModelId", change.entityModel().id());
    spec.bind("timestamp", timestamp.withZoneSameInstant(clock.getZone()).toLocalDateTime());
    if (event != null) {
      spec.bind("eventNumber", event.eventNumber())
          .bind("type", event.type().id())
          .bindNullable("data", byte[].class, event.getMarshalledData());
    }
    if (change.triggerType() != null) {
      spec.bind("triggerId", change.triggerId());
    }
    if (change.conditionTrigger() != null) {
      spec
          .bind("conditionTriggerId", change.conditionTrigger().id())
          .bind("type", change.conditionTrigger().type().id())
          .bindNullable("data", byte[].class, marshal(change.conditionTrigger()))
          .bind("conditionEntityModelId", change.conditionTrigger().entityModel().id())
          .bind("triggerEntityId", change.conditionTrigger().triggerEvent().entityId())
          .bind("triggerEventNumber", change.conditionTrigger().triggerEvent().eventNumber())
          .bind("lastTypes", change.conditionTrigger().lastTypes().stream()
              .map(t -> t.id().toString())
              .collect(Collectors.joining(",")))
          .bind("referenceType", change.conditionTrigger().referenceType().id())
          .bind("indexKey", DataType.uuid().marshal(change.conditionTrigger().indexKey()))
          .bind("correlationId", change.conditionTrigger().correlationId());
    }
    if (change.delayedEvent() != null) {
      ZonedDateTime deadline = change.delayedEvent().after();
      spec
          .bind("eventNumber", change.delayedEvent().eventNumber())
          .bind("type", change.delayedEvent().type().id())
          .bindNullable("data", byte[].class, marshal(change.delayedEvent()))
          .bind("deadline", deadline.withZoneSameInstant(clock.getZone()).toLocalDateTime())
          .bind("correlationId", correlationId);
    }
  }

  private static <T> byte[] marshal(DelayedEvent<T> delayedEvent) {
    return delayedEvent.type().inputDataType().marshal(delayedEvent.data());
  }

  private static <T> byte[] marshal(ConditionTrigger<T> conditionTrigger) {
    return conditionTrigger.type().inputDataType().marshal(conditionTrigger.data());
  }

  public interface Change {

    EntityModel entityModel();
    
    EntityId entityId();
    
    Event<?> newEvent();

    DelayedEvent<?> delayedEvent();

    default ConditionTrigger<?> conditionTrigger() {
      return null;
    }

    /**
     * The type of the stored trigger that caused this change's new event, if any.
     */
    default TriggerType triggerType() {
      return null;
    }

    /**
     * The id of the stored trigger that caused this change's new event. The trigger is deleted together with the new
     * event, so that it will not be triggered again.
     */
    default UUID triggerId() {
      return null;
    }

    static Change fromConditionTrigger(ConditionTrigger<?> conditionTrigger) {
      return new Change() {
        @Override
        public EntityModel entityModel() {
          return conditionTrigger.entityModel();
        }

        @Override
        public EntityId entityId() {
          return new EntityId.UUID(conditionTrigger.entityId());
        }

        @Override
        public Event<?> newEvent() {
          return null;
        }

        @Override
        public DelayedEvent<?> delayedEvent() {
          return null;
        }

        @Override
        public ConditionTrigger<?> conditionTrigger() {
          return conditionTrigger;
        }

        @Override
        public String toString() {
          return entityModel().name() + ":" +
              conditionTrigger.entityId() + ":trigger(condition):[" +
              conditionTrigger.type().name() + "]";
        }

      };
    }

    static Change fromAcceptedEvent(ProcessResult.Accepted<?> acceptedEvent) {
      return new Change() {
        @Override
        public EntityModel entityModel() {
          return acceptedEvent.entityModel();
        }

        @Override
        public EntityId entityId() {
          return new EntityId.UUID(acceptedEvent.event().entityId());
        }

        @Override
        public Event<?> newEvent() {
          return acceptedEvent.event();
        }

        @Override
        public TriggerType triggerType() {
          return acceptedEvent.triggerType();
        }

        @Override
        public UUID triggerId() {
          return acceptedEvent.triggerId();
        }

        @Override
        public DelayedEvent<?> delayedEvent() {
          return null;
        }

        @Override
        public String toString() {
          return entityModel().name() + ":" +
              entityId().value() + ":ev:[" +
              newEvent().typeName() + "]:" +
              newEvent().eventNumber() + ":data=" +
              (newEvent().data() != null ? capString(newEvent().getUnmarshalledData().toString()) : "N/A") +
              (newEvent().type() instanceof BasicEventType.ReadOnly<?,?> ? " (read-only)" : "");
        }

      };
    }

    private static String capString(String s) {
      if (s == null) return null;
      if (s.length() > 50) return s.replace("\n", "↵").substring(0, 50) + "...";
      return s;
    }

    static Change fromDelayedEvent(DelayedEvent<?> delayedEvent) {
      return new Change() {
        @Override
        public EntityModel entityModel() {
          return delayedEvent.entityModel();
        }

        @Override
        public EntityId entityId() {
          return new EntityId.UUID(delayedEvent.entityId());
        }

        @Override
        public Event<?> newEvent() {
          return null;
        }

        @Override
        public DelayedEvent<?> delayedEvent() {
          return delayedEvent;
        }

        @Override
        public String toString() {
          return entityModel().name() + ":" +
              delayedEvent.entityId() + ":ev:[" +
              delayedEvent.type().name() + "]:" +
              delayedEvent.eventNumber() +
              " after " + delayedEvent.after();
        }

      };
    }

  }

  public record OutboxElement(int changeIndex, int messageIndex, UUID requestId, byte[] elementId) {}

  public Flux<OutboxElement> execute(ZonedDateTime timestamp, String correlationId, List<Change> changes) {
    String sql =
        """
        SET XACT_ABORT ON;
        BEGIN TRANSACTION;
        DECLARE @OutboxElement         TABLE (ChangeIndex TINYINT, MessageIndex TINYINT, RequestId UNIQUEIDENTIFIER);
        DECLARE @QueueElement          TABLE (ChangeIndex TINYINT, MessageIndex TINYINT, RequestId UNIQUEIDENTIFIER, ElementId BINARY(8), Guaranteed BIT);
        DECLARE @QueueElementToProcess TABLE (ChangeIndex TINYINT, MessageIndex TINYINT, RequestId UNIQUEIDENTIFIER, ElementId BINARY(8));
        """ + IntStream.range(0, changes.size()).mapToObj(i -> insertSql(
            i,
            "p" + i + "_",
            changes.get(i).entityModel(),
            changes.get(i).newEvent(),
            changes.get(i).delayedEvent(),
            changes.get(i).conditionTrigger(),
            changes.get(i).triggerType()
        )).collect(joining("\n")) +
            """
            INSERT INTO @QueueElementToProcess (ChangeIndex, MessageIndex, RequestId, ElementId)
            SELECT ChangeIndex, MessageIndex, RequestId, ElementId FROM @QueueElement
            WHERE Guaranteed = 0;
            
            SELECT ChangeIndex, MessageIndex, RequestId, ElementId FROM @QueueElementToProcess ORDER BY ChangeIndex ASC, MessageIndex ASC
            COMMIT TRANSACTION;
            """;
    Builder spec = databaseClient.sql(sql).name("ChangeState");
    for (int i = 0; i < changes.size(); i++) {bind(spec.parameterPrefix("p" + i + "_"), timestamp, correlationId, changes.get(i));}
    return spec.map(row -> new OutboxElement(
            row.get("ChangeIndex", Integer.class),
            row.get("MessageIndex", Integer.class),
            row.get("RequestId", UUID.class),
            row.get("ElementId", byte[].class)
        ))
        .all()
        .onErrorMap(PrimaryKeyConstraintViolation.class, primaryKeyConstraintViolationMapper(changes))
        .onErrorMap(UniqueIndexConstraintViolation.class, uniqueIndexConstraintViolationMapper(changes))
        ;
  }

  private String insertSql(
      int changeIndex,
      String parameterPrefix,
      EntityModel entityModel,
      Event<?> event,
      DelayedEvent<?> delayedEvent,
      ConditionTrigger<?> conditionTrigger,
      TriggerType triggerType
  ) {
    String sql =
        """
        DECLARE @entityId{changeIndex} UNIQUEIDENTIFIER
        SELECT @entityId{changeIndex}=:entityId
        """.replace("{changeIndex}", String.valueOf(changeIndex));
    if (event != null && (entityModel instanceof IndexEntityModel || entityModel instanceof IdEntityModel)) {
      sql +=
          """
          INSERT INTO [{schema}].[{table}] (
            EntityId,
            EventNumber,
            Type,
            Timestamp,
            Data
          ) VALUES (
            @entityId{changeIndex},
            :eventNumber,:type,:timestamp,:data
          )
          """.replace("{schema}", schema)
              .replace("{table}", entityModel instanceof IdEntityModel ? "IdEvent" : "IndexEvent")
              .replace("{changeIndex}", String.valueOf(changeIndex));
    } else if (event != null) {
      if (event.eventNumber() == 1) {
        sql +=
            """
            INSERT INTO [{schema}].[Entity] (
              EntityId,
              EntityModelId
            ) VALUES (
              @entityId{changeIndex},
              :entityModelId
            );
            """.replace("{schema}", schema)
                .replace("{changeIndex}", String.valueOf(changeIndex));
      }
      // A delayed trigger holds the number its event would get, so it is stale once that number is taken
      sql +=
          """
          DELETE [{schema}].[DelayedTrigger] FROM [{schema}].[DelayedTrigger] WITH (INDEX([ixEntityId]))
          WHERE EntityId=@entityId{changeIndex} AND EventNumber<=:eventNumber;
          """.replace("{changeIndex}", String.valueOf(changeIndex))
              .replace("{schema}", schema);
      sql +=
          """
          INSERT INTO [{schema}].[Event] (
            EntityId,
            EventNumber,
            Type,
            Timestamp,
            Data
          ) VALUES (
            @entityId{changeIndex},
            :eventNumber,:type,:timestamp,:data
          )
          """.replace("{schema}", schema)
              .replace("{changeIndex}", String.valueOf(changeIndex));
      if (triggerType == TriggerType.CONDITION) {
        sql +=
            """
            DELETE [{schema}].[ConditionTrigger]
            WHERE Id=:triggerId;
            """.replace("{schema}", schema);
      }
    }

    if (conditionTrigger != null) {
      sql +=
          """
          INSERT INTO [{schema}].[ConditionTrigger] (
            Id,
            EntityId,
            EntityModelId,
            TriggerEntityId,
            TriggerEventNumber,
            Type,
            Data,
            Timestamp,
            LastTypes,
            ReferenceType,
            IndexKey,
            CorrelationId
          ) VALUES (
            :conditionTriggerId,
            @entityId{changeIndex},
            :conditionEntityModelId,
            :triggerEntityId,
            :triggerEventNumber,
            :type,:data,:timestamp,:lastTypes,:referenceType,:indexKey,:correlationId
          );
          """.replace("{changeIndex}", String.valueOf(changeIndex))
              .replace("{schema}", schema);
    }

    if (delayedEvent != null) {
      // Replace the previous delayed trigger, which may not have been deleted yet by the new event's change
      sql +=
          """
          DELETE [{schema}].[DelayedTrigger] FROM [{schema}].[DelayedTrigger] WITH (INDEX([ixEntityId]))
          WHERE EntityId=@entityId{changeIndex} AND EventNumber<:eventNumber;
          """.replace("{changeIndex}", String.valueOf(changeIndex))
              .replace("{schema}", schema);
      sql +=
          """
          INSERT INTO [{schema}].[DelayedTrigger] (
            EntityId,
            EntityModelId,
            EventNumber,
            Type,
            Data,
            Deadline,
            CorrelationId,
            Attempt
          ) VALUES (
            @entityId{changeIndex},
            :entityModelId,
            :eventNumber,
            :type,
            :data,
            :deadline,
            :correlationId,
            0
          );
          """.replace("{changeIndex}", String.valueOf(changeIndex))
              .replace("{schema}", schema);
    }
    // Remove comments
    sql = sql.lines().map(line -> line.replaceAll("--.*", "")).collect(joining("\n")) + "\n";
    return sql.replaceAll(":([a-zA-Z0-9_]+)", ":" + parameterPrefix + "$1");
  }

//  private EntityModel childEntity(HttpOutbox.EntityModel thisModel) {
//    return entityModels.stream()
//        .filter(e -> e instanceof HttpOutbox.EntityModel outE && thisModel.equals(outE.parentEntity()))
//        .findFirst()
//        .orElse(null);
//  }

  private Function<PrimaryKeyConstraintViolation, Throwable> primaryKeyConstraintViolationMapper(List<Change> changes) {
    return pkViolation -> {
      if (pkViolation.tableName().equals("Entity")) {
        try {
          EntityId entityId = new EntityId.UUID(UUID.fromString(pkViolation.duplicateKey().trim()));
          return new EventAlreadyExists(
              entityId,
              1,
              changes
          );
        } catch (Exception e) {
          return new EventAlreadyExists(pkViolation.duplicateKey());
        }
      } else if (List.of("Event", "IndexEvent", "IdEvent").contains(pkViolation.tableName())) {
        try {
          EntityId entityId = new EntityId.UUID(UUID.fromString(pkViolation.duplicateKey().split(",")[0]));
          int eventNumber = Integer.parseInt(pkViolation.duplicateKey().split(",")[1].trim());
          return new EventAlreadyExists(
              entityId,
              eventNumber,
              changes
          );
        } catch (Exception e) {
          return new EventAlreadyExists(pkViolation.duplicateKey());
        }
      } else {
        return pkViolation;
      }
    };
  }

  /// The duplicate key of an id event is its type and data, e.g. "f721bf54-891b-41a0-aa63-5d3cbde52437, 0x7B22..."
  private Function<UniqueIndexConstraintViolation, Throwable> uniqueIndexConstraintViolationMapper(List<Change> changes) {
    return indexViolation -> changes.stream()
        .filter(_ -> indexViolation.tableName().equals("IdEvent"))
        .filter(change -> change.entityModel() instanceof IdEntityModel && change.newEvent() != null)
        .map(Change::newEvent)
        .filter(event -> indexViolation.duplicateKey().equalsIgnoreCase(
            event.type().id() + ", 0x" + HexFormat.of().formatHex(event.getMarshalledData())
        ))
        .<Throwable>map(event -> new IdEventAlreadyExists(event, indexViolation.duplicateKey()))
        .findFirst()
        .orElseGet(() -> new RuntimeException("Failed to map index violation exception: " + indexViolation, indexViolation));
  }

}
