package com.github.thxmasj.backend.core.database.postgres;

import static com.github.thxmasj.backend.core.database.postgres.Names.table;

import com.github.thxmasj.backend.core.EntityModel;
import com.github.thxmasj.backend.core.EventType;
import com.github.thxmasj.backend.core.database.Client;
import java.time.Clock;
import java.util.List;
import java.util.Map;

public class ConditionalTriggers extends com.github.thxmasj.backend.core.database.mssql.ConditionalTriggers {

  public ConditionalTriggers(
      Client databaseClient,
      Map<EntityModel, List<EventType<?, ?>>> eventTypesByEntityModel,
      Clock clock,
      String schemaName
  ) {
    // A condition is met when there are index events with the reference type for the index key, and the last event of
    // every entity referenced by those index events has one of the expected types.
    super(
        databaseClient,
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
        """.replace("{ConditionalTrigger}", table(schemaName, "ConditionalTrigger"))
            .replace("{IndexEvent}", table(schemaName, "IndexEvent"))
            .replace("{Event}", table(schemaName, "Event")),
        eventTypesByEntityModel,
        clock
    );
  }

}
