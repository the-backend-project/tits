package com.github.thxmasj.backend.core.database.mssql;

import static com.github.thxmasj.backend.core.database.jdbc.PreparedStatementSupport.nextResultSet;
import static com.github.thxmasj.backend.core.database.jdbc.PreparedStatementSupport.prepare;

import com.github.thxmasj.backend.core.EntityId;
import com.github.thxmasj.backend.core.EntityModel;
import com.github.thxmasj.backend.core.Event;
import com.github.thxmasj.backend.core.EventLog;
import com.github.thxmasj.backend.core.EventType;
import com.github.thxmasj.backend.core.database.MappingFailure;
import com.github.thxmasj.backend.core.database.Parameter;
import com.github.thxmasj.backend.core.database.jdbc.JDBCRow;
import java.sql.ResultSet;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import reactor.core.publisher.Mono;

/// Events of the entity last indexed by a key, i.e. the index entity with the last index event of a type with the key
/// as data, or of the one indexed before it, and so on
public class EventsByLastIndexEvent {

  private final DataSource dataSource;
  private final Clock clock;
  private final String sql;

  public EventsByLastIndexEvent(DataSource dataSource, String schemaName, Clock clock) {
    this.dataSource = dataSource;
    this.clock = clock;
    this.sql =
        """
        SET NOCOUNT ON;
        DECLARE @entityId UNIQUEIDENTIFIER;

        SELECT TOP(1) @entityId=EntityId
        FROM (
          SELECT TOP(:position) EntityId, Sequence
          FROM [{schema}].[IndexEvent] WITH (INDEX(ixData_Type_Sequence))
          WHERE Data=:key AND Type=:type
          ORDER BY Sequence DESC
        ) Indexed
        ORDER BY Sequence ASC;

        SELECT @entityId AS EntityId;

        SELECT EventNumber, Type, Timestamp, Data
        FROM [{schema}].[Event] WITH (INDEX(pkEvent))
        WHERE EntityId=@entityId
        ORDER BY EventNumber;
        """.replace("{schema}", schemaName);
  }

  /// Completes empty if no entity is indexed by the key. If fewer entities than the position are indexed by it, the
  /// first one is given.
  public Mono<EventLog> execute(EntityModel entityModel, List<EventType<?, ?>> eventTypes, UUID type, byte[] key, int position) {
    return Mono.fromCallable(() -> {
      try (
          var connection = dataSource.getConnection();
          var statement = prepare(
              sql,
              Map.of(
                  "type", new Parameter<>(UUID.class, type),
                  "key", new Parameter<>(byte[].class, key),
                  "position", new Parameter<>(Integer.class, position)
              ),
              connection
          )
      ) {
        ResultSet rs = nextResultSet(statement, statement.execute());
        rs.next();
        UUID entityId = rs.getObject("EntityId", UUID.class);
        if (entityId == null)
          return null;
        rs = nextResultSet(statement, statement.getMoreResults());
        var eventMapper = Mappers.eventMapper(eventTypes, clock);
        List<Event<?>> events = new ArrayList<>();
        while (rs.next()) {
          try {
            events.add(eventMapper.apply(new EntityId.UUID(entityId), new JDBCRow(rs)));
          } catch (MappingFailure e) {
            throw new MappingFailure("EventsByLastIndexEvent: Failed to map row to event for entity model " + entityModel.name(), e);
          }
        }
        return new EventLog(entityModel, new EntityId.UUID(entityId), Collections.unmodifiableList(events));
      }
    });
  }

}
