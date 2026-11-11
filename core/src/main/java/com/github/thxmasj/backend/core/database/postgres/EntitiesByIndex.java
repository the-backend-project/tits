package com.github.thxmasj.backend.core.database.postgres;

import static com.github.thxmasj.backend.core.database.postgres.Names.table;

import com.github.thxmasj.backend.core.EntityModel;
import com.github.thxmasj.backend.core.EventType;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;

public class EntitiesByIndex extends com.github.thxmasj.backend.core.database.mssql.EntitiesByIndex {

  /// Maps the events of each entity model with its own event types
  public EntitiesByIndex(
      DataSource dataSource,
      Map<? extends EntityModel, List<EventType<?, ?>>> eventTypesByEntityModel,
      String schemaName,
      Clock clock
  ) {
    super(
        dataSource,
        eventTypesByEntityModel,
        clock,
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
        """.replace("{Event}", table(schemaName, "Event"))
            .replace("{Entity}", table(schemaName, "Entity"))
            .replace("{IndexEvent}", table(schemaName, "IndexEvent"))
    );
  }

}
