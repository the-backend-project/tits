package com.github.thxmasj.backend.core.database.postgres;

import static com.github.thxmasj.backend.core.database.postgres.Names.table;

import com.github.thxmasj.backend.core.EntityModel;
import com.github.thxmasj.backend.core.EventType;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;

/// Events of the entity having an id, found by the id event of its id entity
public class EventsByIdEvent extends com.github.thxmasj.backend.core.database.mssql.EventsByIdEvent {

  public EventsByIdEvent(
      DataSource dataSource,
      Map<EntityModel, List<EventType<?, ?>>> eventTypesByEntityModel,
      String schemaName,
      Clock clock
  ) {
    super(dataSource, eventTypesByEntityModel, clock, sql(schemaName));
  }

  // The entity is selected by both statements, as PostgreSQL has no variables to keep it in. The left join gives a
  // row of nulls if no entity has the id.
  private static String sql(String schemaName) {
    String entity =
        """
        SELECT e.EntityId, e.EntityModelId
        FROM {IdEvent} ie
          INNER JOIN {Entity} e ON e.EntityId = ie.EntityId
        WHERE ie.Type=:type AND ie.Data=:data
        LIMIT 1
        """;
    return
        """
        SELECT Found.EntityId, Found.EntityModelId
        FROM (VALUES (1)) AS One (Value)
          LEFT JOIN ({entity}) Found ON TRUE;

        SELECT EventNumber, Type, Timestamp, Data
        FROM {Event}
        WHERE EntityId=(SELECT EntityId FROM ({entity}) Found)
        ORDER BY EventNumber;
        """.replace("{entity}", entity)
            .replace("{IdEvent}", table(schemaName, "IdEvent"))
            .replace("{Entity}", table(schemaName, "Entity"))
            .replace("{Event}", table(schemaName, "Event"));
  }

}
