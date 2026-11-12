package com.github.thxmasj.backend.database.pgsql;

import static com.github.thxmasj.backend.database.pgsql.Names.table;
import static java.util.stream.Collectors.joining;

import com.github.thxmasj.backend.core.ConditionalTrigger;
import com.github.thxmasj.backend.core.DelayedEvent;
import com.github.thxmasj.backend.core.EntityModel;
import com.github.thxmasj.backend.core.Event;
import com.github.thxmasj.backend.core.IdEntityModel;
import com.github.thxmasj.backend.core.IndexEntityModel;
import com.github.thxmasj.backend.core.TriggerType;
import com.github.thxmasj.backend.core.database.DatabaseClient;
import java.time.Clock;
import java.time.ZonedDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.IntStream;
import reactor.core.publisher.Flux;

/// Stores the changes in a single round trip without an explicit transaction. PostgreSQL then executes them in a
/// single implicit transaction, which is rolled back if any of them fails.
@SuppressWarnings("StringConcatenationInLoop")
class ChangeState extends com.github.thxmasj.backend.core.database.ChangeState {

  private final String schema;

  ChangeState(DatabaseClient databaseClient, String schema, Clock clock) {
    super(databaseClient, clock);
    this.schema = schema;
  }

  /// Stores nothing if there are no changes, as PostgreSQL would get no statements
  @Override
  public Flux<OutboxElement> execute(ZonedDateTime timestamp, String correlationId, List<Change> changes) {
    return changes.isEmpty() ? Flux.empty() : super.execute(timestamp, correlationId, changes);
  }

  @Override
  protected String sql(List<Change> changes) {
    return IntStream.range(0, changes.size()).mapToObj(i -> insertSql(
        "p" + i + "_",
        changes.get(i).entityModel(),
        changes.get(i).newEvent(),
        changes.get(i).delayedEvent(),
        changes.get(i).conditionalTrigger(),
        changes.get(i).triggerType()
    )).collect(joining("\n"));
  }

  /// The duplicate key of an id event, as reported by PostgreSQL, e.g. "f721bf54-891b-41a0-aa63-5d3cbde52437, \x7b22..."
  @Override
  protected String duplicateKey(Event<?> idEvent) {
    return idEvent.type().id() + ", \\x" + HexFormat.of().formatHex(idEvent.getMarshalledData());
  }

  private String insertSql(
      String parameterPrefix,
      EntityModel entityModel,
      Event<?> event,
      DelayedEvent<?> delayedEvent,
      ConditionalTrigger<?> conditionalTrigger,
      TriggerType triggerType
  ) {
    String sql = "";
    if (event != null && (entityModel instanceof IndexEntityModel || entityModel instanceof IdEntityModel)) {
      sql +=
          """
          INSERT INTO {table} (
            EntityId,
            EventNumber,
            Type,
            Timestamp,
            Data
          ) VALUES (
            :entityId,
            :eventNumber,:type,:timestamp,:data
          );
          """.replace("{table}", table(schema, entityModel instanceof IdEntityModel ? "IdEvent" : "IndexEvent"));
    } else if (event != null) {
      if (event.eventNumber() == 1) {
        sql +=
            """
            INSERT INTO {Entity} (
              EntityId,
              EntityModelId
            ) VALUES (
              :entityId,
              :entityModelId
            );
            """.replace("{Entity}", table(schema, "Entity"));
      }
      // A delayed trigger holds the number its event would get, so it is stale once that number is taken
      sql +=
          """
          DELETE FROM {DelayedTrigger}
          WHERE EntityId=:entityId AND EventNumber<=:eventNumber;
          """.replace("{DelayedTrigger}", table(schema, "DelayedTrigger"));
      sql +=
          """
          INSERT INTO {Event} (
            EntityId,
            EventNumber,
            Type,
            Timestamp,
            Data
          ) VALUES (
            :entityId,
            :eventNumber,:type,:timestamp,:data
          );
          """.replace("{Event}", table(schema, "Event"));
      if (triggerType == TriggerType.CONDITIONAL) {
        sql +=
            """
            DELETE FROM {ConditionalTrigger}
            WHERE Id=:triggerId;
            """.replace("{ConditionalTrigger}", table(schema, "ConditionalTrigger"));
      }
    }

    if (conditionalTrigger != null) {
      sql +=
          """
          INSERT INTO {ConditionalTrigger} (
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
            :conditionalTriggerId,
            :entityId,
            :conditionEntityModelId,
            :triggerEntityId,
            :triggerEventNumber,
            :type,:data,:timestamp,:lastTypes,:referenceType,:indexKey,:correlationId
          );
          """.replace("{ConditionalTrigger}", table(schema, "ConditionalTrigger"));
    }

    if (delayedEvent != null) {
      // Replace the previous delayed trigger, which may not have been deleted yet by the new event's change
      sql +=
          """
          DELETE FROM {DelayedTrigger}
          WHERE EntityId=:entityId AND EventNumber<:eventNumber;
          """.replace("{DelayedTrigger}", table(schema, "DelayedTrigger"));
      sql +=
          """
          INSERT INTO {DelayedTrigger} (
            EntityId,
            EntityModelId,
            EventNumber,
            Type,
            Data,
            Deadline,
            CorrelationId,
            Attempt
          ) VALUES (
            :entityId,
            :entityModelId,
            :eventNumber,
            :type,
            :data,
            :deadline,
            :correlationId,
            0
          );
          """.replace("{DelayedTrigger}", table(schema, "DelayedTrigger"));
    }
    return sql.replaceAll(":([a-zA-Z0-9_]+)", ":" + parameterPrefix + "$1");
  }

}
