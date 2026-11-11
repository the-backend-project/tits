package com.github.thxmasj.backend.core.database.postgres;

import static com.github.thxmasj.backend.core.database.postgres.Names.table;

import java.time.Clock;
import javax.sql.DataSource;

/// Events of the entity with the last index event of a group, i.e. the index entity with the last index event of a type
/// whose data starts with the marshalled group, ordered by data, or of the one before it, and so on
public class EventsByLastIndexEvent extends com.github.thxmasj.backend.core.database.mssql.EventsByLastIndexEvent {

  public EventsByLastIndexEvent(DataSource dataSource, String schemaName, Clock clock) {
    super(dataSource, clock, sql(schemaName));
  }

  // The entity is selected by both statements, as PostgreSQL has no variables to keep it in. The left join gives a
  // row of nulls if the group has no index events.
  private static String sql(String schemaName) {
    String entity =
        """
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
        """;
    return
        """
        SELECT Found.EntityId
        FROM (VALUES (1)) AS One (Value)
          LEFT JOIN ({entity}) Found ON TRUE;

        SELECT EventNumber, Type, Timestamp, Data
        FROM {Event}
        WHERE EntityId=(SELECT EntityId FROM ({entity}) Found)
        ORDER BY EventNumber;
        """.replace("{entity}", entity)
            .replace("{IndexEvent}", table(schemaName, "IndexEvent"))
            .replace("{Event}", table(schemaName, "Event"));
  }

}
