package com.github.thxmasj.backend.database.mssql;

/// The SQL of the queries of the state machine's schema in SQL Server
final class Queries {

  private Queries() {}

  /// The events of an entity in one of the event tables
  static String eventsByEntityId(String schema, String table) {
    return
        """
        SELECT EventNumber, Type, Timestamp, Data
        FROM [{schema}].[{table}] WITH (INDEX(pk{table}))
        WHERE EntityId=:entityId
        ORDER BY EventNumber;
        """.replace("{schema}", schema)
            .replace("{table}", table);
  }

  static String eventsByIdEvent(String schema) {
    return
        """
        SET NOCOUNT ON;
        DECLARE @entityId UNIQUEIDENTIFIER, @entityModelId UNIQUEIDENTIFIER;

        SELECT TOP(1) @entityId=e.EntityId, @entityModelId=e.EntityModelId
        FROM [{schema}].[IdEvent] ie WITH (INDEX(ixType_Data))
          INNER JOIN [{schema}].[Entity] e ON e.EntityId = ie.EntityId
        WHERE ie.Type=:type AND ie.Data=:data;

        SELECT @entityId AS EntityId, @entityModelId AS EntityModelId, EventNumber, Type, Timestamp, Data
        FROM [{schema}].[Event] WITH (INDEX(pkEvent))
        WHERE EntityId=@entityId
        ORDER BY EventNumber;
        """.replace("{schema}", schema);
  }

  static String eventsByLastIndexEvent(String schema) {
    return
        """
        SET NOCOUNT ON;
        DECLARE @entityId UNIQUEIDENTIFIER;

        SELECT TOP(1) @entityId=EntityId
        FROM (
          SELECT TOP(:position) EntityId, Data
          FROM [{schema}].[IndexEvent] WITH (INDEX(ixType_Data))
          WHERE Type=:type AND Data >= :group AND Data < :groupEnd
          ORDER BY Data DESC
        ) Indexed
        ORDER BY Data ASC;

        SELECT @entityId AS EntityId, EventNumber, Type, Timestamp, Data
        FROM [{schema}].[Event] WITH (INDEX(pkEvent))
        WHERE EntityId=@entityId
        ORDER BY EventNumber;
        """.replace("{schema}", schema);
  }

  static String entitiesByIndex(String schema) {
    return
        """
        SELECT
          e.EntityId,
          ent.EntityModelId,
          e.EventNumber,
          e.Type,
          e.Timestamp,
          e.Data
        FROM [{schema}].Event e
          INNER JOIN [{schema}].[Entity] ent ON ent.EntityId = e.EntityId
          INNER JOIN [{schema}].IndexEvent ie ON e.EntityId=ie.EntityId
        WHERE ie.Data=:value AND ie.Type=:referenceType
        ORDER BY e.EntityId, e.EventNumber;
        """.replace("{schema}", schema);
  }

  static String nextDelayedTrigger(String schema) {
    return
        """
        DECLARE @processing TABLE (
          EntityId UNIQUEIDENTIFIER,
          EntityModelId UNIQUEIDENTIFIER,
          EventNumber SMALLINT,
          Type UNIQUEIDENTIFIER,
          Data VARBINARY(MAX),
          Deadline DATETIME2(2),
          Attempt INT,
          NextAttemptAt DATETIME2(2),
          CorrelationId VARCHAR(36)
        )

        INSERT INTO @processing
        SELECT TOP (1)
          EntityId,
          EntityModelId,
          EventNumber,
          Type,
          Data,
          Deadline,
          Attempt,
          DATEADD(
                  millisecond,
                  1000 * (SELECT MIN(i) FROM (VALUES
                    (:maximumBackoff),
                    (:minimumBackoff*POWER(:backoffPowerBase, (SELECT MIN(j) FROM (VALUES (Attempt), (50)) as N(j))))
                  ) AS T(i)),
                  :now
                ),
          CorrelationId
        FROM [{schema}].[DelayedTrigger] WITH (INDEX([ixDeadline]))
        WHERE Deadline < :now
        ORDER BY Deadline ASC

        UPDATE [{schema}].[DelayedTrigger] SET
          Deadline=(SELECT NextAttemptAt FROM @processing),
          Attempt=Attempt+1
        FROM [{schema}].[DelayedTrigger] WITH (INDEX([ixEntityId]))
        WHERE
          EntityId=(SELECT EntityId FROM @processing) AND
          EventNumber=(SELECT EventNumber FROM @processing) AND
          Deadline=(SELECT Deadline FROM @processing)

        IF @@ROWCOUNT = 1
          SELECT EntityId, EntityModelId, EventNumber, Type, Data, NextAttemptAt, CorrelationId
          FROM @processing
        ELSE
          SELECT TOP 0 EntityId, EntityModelId, EventNumber, Type, Data, NextAttemptAt, CorrelationId
          FROM @processing
        """.replace("{schema}", schema);
  }

  static String deleteDelayedTrigger(String schema) {
    return "DELETE FROM [{schema}].[DelayedTrigger] WHERE EntityId = :entityId AND EventNumber = :eventNumber"
        .replace("{schema}", schema);
  }

  // A condition is met when there are index events with the reference type for the index key, and the last event of
  // every entity referenced by those index events has one of the expected types.
  static String conditionalTriggers(String schema) {
    return
        """
        SELECT TOP (:limit) ce.Id, ce.EntityId, ce.EntityModelId, ce.TriggerEntityId, ce.TriggerEventNumber, ce.Type, ce.Data, ce.Timestamp, ce.LastTypes, ce.ReferenceType, ce.IndexKey, ce.CorrelationId
        FROM [{schema}].[ConditionalTrigger] ce
        WHERE EXISTS (
            SELECT 1 FROM [{schema}].[IndexEvent] ie
            WHERE ie.Data = ce.IndexKey AND ie.Type = ce.ReferenceType
          )
          AND NOT EXISTS (
            SELECT 1 FROM [{schema}].[IndexEvent] ie
            WHERE ie.Data = ce.IndexKey AND ie.Type = ce.ReferenceType
              AND NOT EXISTS (
                SELECT 1 FROM [{schema}].[Event] e
                WHERE e.EntityId = ie.EntityId
                  AND e.Type IN (SELECT CAST(lt.value AS UNIQUEIDENTIFIER) FROM STRING_SPLIT(ce.LastTypes, ',') lt)
                  AND e.EventNumber = (SELECT MAX(e2.EventNumber) FROM [{schema}].[Event] e2 WHERE e2.EntityId = ie.EntityId)
              )
          )
        ORDER BY ce.Timestamp ASC
        """.replace("{schema}", schema);
  }

}
