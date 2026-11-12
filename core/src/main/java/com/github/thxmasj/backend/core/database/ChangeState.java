package com.github.thxmasj.backend.core.database;

import com.github.thxmasj.backend.core.BasicEventType;
import com.github.thxmasj.backend.core.ConditionalTrigger;
import com.github.thxmasj.backend.core.DataType;
import com.github.thxmasj.backend.core.DelayedEvent;
import com.github.thxmasj.backend.core.EntityModel;
import com.github.thxmasj.backend.core.Event;
import com.github.thxmasj.backend.core.IdEntityModel;
import com.github.thxmasj.backend.core.StateMachine.ProcessResult;
import com.github.thxmasj.backend.core.TriggerType;
import com.github.thxmasj.backend.core.database.DatabaseClient.PrimaryKeyConstraintViolation;
import com.github.thxmasj.backend.core.database.DatabaseClient.Query.Builder;
import com.github.thxmasj.backend.core.database.DatabaseClient.UniqueIndexConstraintViolation;
import java.time.Clock;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import reactor.core.publisher.Flux;

/// Stores the changes of a transition atomically
public abstract class ChangeState {

  private final DatabaseClient databaseClient;
  private final Clock clock;

  protected ChangeState(DatabaseClient databaseClient, Clock clock) {
    this.databaseClient = databaseClient;
    this.clock = clock;
  }

  private void bind(Builder spec, ZonedDateTime timestamp, String correlationId, Change change) {
    Event<?> event = change.newEvent();
    if (change.entityId() != null)
      spec.bind("entityId", change.entityId());
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
    if (change.conditionalTrigger() != null) {
      spec
          .bind("conditionalTriggerId", change.conditionalTrigger().id())
          .bind("type", change.conditionalTrigger().type().id())
          .bindNullable("data", byte[].class, marshal(change.conditionalTrigger()))
          .bind("conditionEntityModelId", change.conditionalTrigger().entityModel().id())
          .bind("triggerEntityId", change.conditionalTrigger().triggerEvent().entityId())
          .bind("triggerEventNumber", change.conditionalTrigger().triggerEvent().eventNumber())
          .bind("lastTypes", change.conditionalTrigger().lastTypes().stream()
              .map(t -> t.id().toString())
              .collect(Collectors.joining(",")))
          .bind("referenceType", change.conditionalTrigger().referenceType().id())
          .bind("indexKey", DataType.uuid().marshal(change.conditionalTrigger().indexKey()))
          .bind("correlationId", change.conditionalTrigger().correlationId());
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

  private static <T> byte[] marshal(ConditionalTrigger<T> conditionalTrigger) {
    return conditionalTrigger.type().inputDataType().marshal(conditionalTrigger.data());
  }

  public interface Change {

    EntityModel entityModel();
    
    UUID entityId();
    
    Event<?> newEvent();

    DelayedEvent<?> delayedEvent();

    default ConditionalTrigger<?> conditionalTrigger() {
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

    static Change fromConditionalTrigger(ConditionalTrigger<?> conditionalTrigger) {
      return new Change() {
        @Override
        public EntityModel entityModel() {
          return conditionalTrigger.entityModel();
        }

        @Override
        public UUID entityId() {
          return conditionalTrigger.entityId();
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
        public ConditionalTrigger<?> conditionalTrigger() {
          return conditionalTrigger;
        }

        @Override
        public String toString() {
          return entityModel().name() + ":" +
              conditionalTrigger.entityId() + ":trigger(conditional):[" +
              conditionalTrigger.type().name() + "]";
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
        public UUID entityId() {
          return acceptedEvent.event().entityId();
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
              entityId() + ":ev:[" +
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
        public UUID entityId() {
          return delayedEvent.entityId();
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
    Builder spec = databaseClient.sql(sql(changes)).name("ChangeState");
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

  /// The SQL storing the changes in a single transaction, in the dialect of the database. Parameters are prefixed with
  /// the index of their change, as in "p0_". It selects the outbox elements to process, if any.
  protected abstract String sql(List<Change> changes);

  private Function<PrimaryKeyConstraintViolation, Throwable> primaryKeyConstraintViolationMapper(List<Change> changes) {
    return pkViolation -> {
      if (pkViolation.tableName().equals("Entity")) {
        try {
          UUID entityId = UUID.fromString(pkViolation.duplicateKey().trim());
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
          UUID entityId = UUID.fromString(pkViolation.duplicateKey().split(",")[0]);
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

  /// The duplicate key of an id event, as reported by the database, i.e. its type and data
  protected abstract String duplicateKey(Event<?> idEvent);

  private Function<UniqueIndexConstraintViolation, Throwable> uniqueIndexConstraintViolationMapper(List<Change> changes) {
    return indexViolation -> changes.stream()
        .filter(_ -> indexViolation.tableName().equals("IdEvent"))
        .filter(change -> change.entityModel() instanceof IdEntityModel && change.newEvent() != null)
        .map(Change::newEvent)
        .filter(event -> indexViolation.duplicateKey().equalsIgnoreCase(duplicateKey(event)))
        .<Throwable>map(event -> new IdEventAlreadyExists(event, indexViolation.duplicateKey()))
        .findFirst()
        .orElseGet(() -> new RuntimeException("Failed to map index violation exception: " + indexViolation, indexViolation));
  }

}
