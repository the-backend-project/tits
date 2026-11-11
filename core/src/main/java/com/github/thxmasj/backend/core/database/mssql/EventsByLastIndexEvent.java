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
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import reactor.core.publisher.Mono;

/// Events of the entity with the last index event of a group, i.e. the index entity with the last index event of a type
/// whose data starts with the marshalled group, ordered by data, or of the one before it, and so on
public class EventsByLastIndexEvent {

  private final DataSource dataSource;
  private final Clock clock;
  private final String sql;

  public EventsByLastIndexEvent(DataSource dataSource, String schemaName, Clock clock) {
    this(
        dataSource,
        clock,
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

        SELECT @entityId AS EntityId;

        SELECT EventNumber, Type, Timestamp, Data
        FROM [{schema}].[Event] WITH (INDEX(pkEvent))
        WHERE EntityId=@entityId
        ORDER BY EventNumber;
        """.replace("{schema}", schemaName)
    );
  }

  /// @param sql the SQL selecting a row with the entity id, which is null if the group has no index events, and then
  ///            the events of the entity, in the dialect of the database
  protected EventsByLastIndexEvent(DataSource dataSource, Clock clock, String sql) {
    this.dataSource = dataSource;
    this.clock = clock;
    this.sql = sql;
  }

  /// Completes empty if the group has no index events. If it has fewer index events than the position, the entity of
  /// the first one is given.
  ///
  /// @param group the marshalled group, i.e. the prefix of the data of its index events
  public Mono<EventLog> execute(EntityModel entityModel, List<EventType<?, ?>> eventTypes, UUID type, byte[] group, int position) {
    return Mono.fromCallable(() -> {
      try (
          var connection = dataSource.getConnection();
          var statement = prepare(
              sql,
              Map.of(
                  "type", new Parameter<>(UUID.class, type),
                  "group", new Parameter<>(byte[].class, group),
                  "groupEnd", new Parameter<>(byte[].class, end(group)),
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

  /// The least value greater than all values starting with the prefix
  private static byte[] end(byte[] prefix) {
    byte[] end = Arrays.copyOf(prefix, prefix.length);
    for (int i = end.length - 1; i >= 0; i--) {
      if (end[i] != (byte) 0xFF) {
        end[i]++;
        return Arrays.copyOf(end, i + 1);
      }
    }
    throw new IllegalArgumentException("Prefix has no end, as all its bytes are 0xFF");
  }

}
