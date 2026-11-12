package com.github.thxmasj.backend.database.pgsql;

import static com.github.thxmasj.backend.database.pgsql.Names.table;

/// The SQL of the queries of the state machine's schema in PostgreSQL. It has no index hints, as PostgreSQL does not
/// support them.
final class Queries {

  private Queries() {}

  /// The events of an entity in one of the event tables
  static String eventsByEntityId(String schema, String eventTable) {
    return
        """
        SELECT EventNumber, Type, Timestamp, Data
        FROM {table}
        WHERE EntityId=:entityId
        ORDER BY EventNumber;
        """.replace("{table}", table(schema, eventTable));
  }

  static String eventsByIdEvent(String schema) {
    return
        """
        SELECT Found.EntityId, Found.EntityModelId, ev.EventNumber, ev.Type, ev.Timestamp, ev.Data
        FROM (
          SELECT e.EntityId, e.EntityModelId
          FROM {IdEvent} ie
            INNER JOIN {Entity} e ON e.EntityId = ie.EntityId
          WHERE ie.Type=:type AND ie.Data=:data
          LIMIT 1
        ) Found
          INNER JOIN {Event} ev ON ev.EntityId = Found.EntityId
        ORDER BY ev.EventNumber;
        """.replace("{IdEvent}", table(schema, "IdEvent"))
            .replace("{Entity}", table(schema, "Entity"))
            .replace("{Event}", table(schema, "Event"));
  }

  static String eventsByLastIndexEvent(String schema) {
    return
        """
        SELECT Found.EntityId, ev.EventNumber, ev.Type, ev.Timestamp, ev.Data
        FROM (
          SELECT EntityId
          FROM (
            SELECT EntityId, Data
            FROM {IndexEvent}
            WHERE Type=:type AND Data >= :group AND Data < :groupEnd
            ORDER BY Data DESC
            LIMIT :position
          ) Indexed
          ORDER BY Data ASC
          LIMIT 1
        ) Found
          INNER JOIN {Event} ev ON ev.EntityId = Found.EntityId
        ORDER BY ev.EventNumber;
        """.replace("{IndexEvent}", table(schema, "IndexEvent"))
            .replace("{Event}", table(schema, "Event"));
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
        FROM {Event} e
          INNER JOIN {Entity} ent ON ent.EntityId = e.EntityId
          INNER JOIN {IndexEvent} ie ON e.EntityId=ie.EntityId
        WHERE ie.Data=:value AND ie.Type=:referenceType
        ORDER BY e.EntityId, e.EventNumber;
        """.replace("{Event}", table(schema, "Event"))
            .replace("{Entity}", table(schema, "Entity"))
            .replace("{IndexEvent}", table(schema, "IndexEvent"));
  }

  // Postpone the delayed trigger with the earliest passed deadline with an exponential backoff. Concurrent resolvers
  // skip it while it is locked, and do not find it afterwards as its deadline has not passed.
  static String nextDelayedTrigger(String schema) {
    return
        """
        UPDATE {DelayedTrigger} t SET
          Deadline=n.NextAttemptAt,
          Attempt=t.Attempt+1
        FROM (
          SELECT
            EntityId,
            EventNumber,
            Deadline,
            :now + make_interval(secs => LEAST(
              :maximumBackoff,
              :minimumBackoff*POWER(:backoffPowerBase, LEAST(Attempt, 50))
            )) AS NextAttemptAt
          FROM {DelayedTrigger}
          WHERE Deadline < :now
          ORDER BY Deadline ASC
          LIMIT 1
          FOR UPDATE SKIP LOCKED
        ) n
        WHERE
          t.EntityId=n.EntityId AND
          t.EventNumber=n.EventNumber AND
          t.Deadline=n.Deadline
        RETURNING t.EntityId, t.EntityModelId, t.EventNumber, t.Type, t.Data, t.Deadline AS NextAttemptAt, t.CorrelationId
        """.replace("{DelayedTrigger}", table(schema, "DelayedTrigger"));
  }

  static String deleteDelayedTrigger(String schema) {
    return "DELETE FROM {DelayedTrigger} WHERE EntityId = :entityId AND EventNumber = :eventNumber"
        .replace("{DelayedTrigger}", table(schema, "DelayedTrigger"));
  }

  // A condition is met when there are index events with the reference type for the index key, and the last event of
  // every entity referenced by those index events has one of the expected types.
  static String conditionalTriggers(String schema) {
    return
        """
        SELECT ce.Id, ce.EntityId, ce.EntityModelId, ce.TriggerEntityId, ce.TriggerEventNumber, ce.Type, ce.Data, ce.Timestamp, ce.LastTypes, ce.ReferenceType, ce.IndexKey, ce.CorrelationId
        FROM {ConditionalTrigger} ce
        WHERE EXISTS (
            SELECT 1 FROM {IndexEvent} ie
            WHERE ie.Data = ce.IndexKey AND ie.Type = ce.ReferenceType
          )
          AND NOT EXISTS (
            SELECT 1 FROM {IndexEvent} ie
            WHERE ie.Data = ce.IndexKey AND ie.Type = ce.ReferenceType
              AND NOT EXISTS (
                SELECT 1 FROM {Event} e
                WHERE e.EntityId = ie.EntityId
                  AND e.Type = ANY (CAST(string_to_array(ce.LastTypes, ',') AS UUID[]))
                  AND e.EventNumber = (SELECT MAX(e2.EventNumber) FROM {Event} e2 WHERE e2.EntityId = ie.EntityId)
              )
          )
        ORDER BY ce.Timestamp ASC
        LIMIT :limit
        """.replace("{ConditionalTrigger}", table(schema, "ConditionalTrigger"))
            .replace("{IndexEvent}", table(schema, "IndexEvent"))
            .replace("{Event}", table(schema, "Event"));
  }

}
