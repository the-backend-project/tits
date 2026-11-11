package com.github.thxmasj.backend.database.mssql;

import static java.util.stream.Collectors.joining;

import com.github.thxmasj.backend.core.ConditionalTrigger;
import com.github.thxmasj.backend.core.DelayedEvent;
import com.github.thxmasj.backend.core.EntityModel;
import com.github.thxmasj.backend.core.Event;
import com.github.thxmasj.backend.core.IdEntityModel;
import com.github.thxmasj.backend.core.IndexEntityModel;
import com.github.thxmasj.backend.core.TriggerType;
import com.github.thxmasj.backend.core.database.Client;
import java.time.Clock;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.IntStream;

/// Stores the changes in a single batch with an explicit transaction
class ChangeState extends com.github.thxmasj.backend.core.database.ChangeState {

  private final String schema;

  ChangeState(Client databaseClient, String schema, Clock clock) {
    super(databaseClient, clock);
    this.schema = schema;
  }

  @Override
  protected String sql(List<Change> changes) {
    return
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
            changes.get(i).conditionalTrigger(),
            changes.get(i).triggerType()
        )).collect(joining("\n")) +
            """
            INSERT INTO @QueueElementToProcess (ChangeIndex, MessageIndex, RequestId, ElementId)
            SELECT ChangeIndex, MessageIndex, RequestId, ElementId FROM @QueueElement
            WHERE Guaranteed = 0;
            
            SELECT ChangeIndex, MessageIndex, RequestId, ElementId FROM @QueueElementToProcess ORDER BY ChangeIndex ASC, MessageIndex ASC
            COMMIT TRANSACTION;
            """;
  }

  private String insertSql(
      int changeIndex,
      String parameterPrefix,
      EntityModel entityModel,
      Event<?> event,
      DelayedEvent<?> delayedEvent,
      ConditionalTrigger<?> conditionalTrigger,
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
      if (triggerType == TriggerType.CONDITIONAL) {
        sql +=
            """
            DELETE [{schema}].[ConditionalTrigger]
            WHERE Id=:triggerId;
            """.replace("{schema}", schema);
      }
    }

    if (conditionalTrigger != null) {
      sql +=
          """
          INSERT INTO [{schema}].[ConditionalTrigger] (
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

  /// The duplicate key of an id event, as reported by SQL Server, e.g.
  /// "f721bf54-891b-41a0-aa63-5d3cbde52437, 0x7B22..."
  @Override
  protected String duplicateKey(Event<?> idEvent) {
    return idEvent.type().id() + ", 0x" + HexFormat.of().formatHex(idEvent.getMarshalledData());
  }

}
