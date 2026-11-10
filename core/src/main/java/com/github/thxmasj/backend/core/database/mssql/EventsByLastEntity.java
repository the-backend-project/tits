package com.github.thxmasj.backend.core.database.mssql;

import static com.github.thxmasj.backend.core.database.jdbc.PreparedStatementSupport.prepare;

import com.github.thxmasj.backend.core.EntityId;
import com.github.thxmasj.backend.core.EntityModel;
import com.github.thxmasj.backend.core.Event;
import com.github.thxmasj.backend.core.EventLog;
import com.github.thxmasj.backend.core.EventType;
import com.github.thxmasj.backend.core.SecondaryIdModel;
import com.github.thxmasj.backend.core.SecondaryIdModel.Group;
import com.github.thxmasj.backend.core.database.Client.MssqlException;
import com.github.thxmasj.backend.core.database.EntityGroupNotInitialised;
import com.github.thxmasj.backend.core.database.Parameter;
import com.microsoft.sqlserver.jdbc.SQLServerException;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;
import reactor.core.publisher.Mono;

/// Events of the entity having the last id of a group, or the one before, and so on. The members of a group are the id
/// entities having an index event keyed by the group, ordered by creation.
public class EventsByLastEntity {

  private final DataSource dataSource;
  private final Clock clock;
  private final String sql;

  public EventsByLastEntity(
      DataSource dataSource,
      String schemaName,
      Clock clock
  ) {
    this.dataSource = dataSource;
    this.clock = clock;
    this.sql =
        """
        DECLARE @entityId UNIQUEIDENTIFIER;
        DECLARE @Group TABLE (EntityId UNIQUEIDENTIFIER, Timestamp DATETIME2, EventNumber SMALLINT);

        INSERT INTO @Group (EntityId, Timestamp, EventNumber)
        SELECT TOP(:lastPosition) EntityId, Timestamp, EventNumber
        FROM [{schema}].[IndexEvent] WITH (INDEX(ixData))
        WHERE Data=:group AND Type=:type
        ORDER BY Timestamp DESC, EventNumber DESC;

        IF (SELECT COUNT(*) FROM @Group) < 1
          THROW 50000, 'Entity group not initialised', 1

        SELECT TOP(1) @entityId=EntityId FROM @Group ORDER BY Timestamp ASC, EventNumber ASC

        SELECT @entityId

        SELECT EventNumber, Type, Timestamp, Data
        FROM [{schema}].[Event] WITH (INDEX(pkEvent))
        WHERE EntityId=@entityId
        ORDER BY EventNumber;
        """.replace("{schema}", schemaName);
  }

  public Mono<EventLog> execute(EntityModel entityModel, List<EventType<?, ?>> eventTypes, SecondaryIdModel<?> idModel, Object entityGroup, int lastPosition) {
    Objects.requireNonNull(entityModel);
    Objects.requireNonNull(entityGroup);
    return Mono.fromCallable(() -> {
          try (
              var connection = dataSource.getConnection();
              var statement = prepare(
                  sql,
                  Map.of(
                      "lastPosition", new Parameter<>(Integer.class, lastPosition),
                      "group", new Parameter<>(byte[].class, Group.key(entityGroup)),
                      "type", new Parameter<>(UUID.class, idModel.id())
                  ),
                  connection
              )
          ) {
        statement.execute();
        ResultSet rs = statement.getResultSet();
        if (rs == null) {
          // TODO: The first result is an update count? (see javadoc of getResultSet())
          if (!statement.getMoreResults()) throw new RuntimeException("More results expected");
          rs = statement.getResultSet();
        }
        if (!rs.next()) {
          if (!statement.getMoreResults()) throw new RuntimeException("No result with entity id. SQL is:\n" + sql);
          rs = statement.getResultSet();
          if (!rs.next()) throw new RuntimeException("No result with entity id after trying more results as well");
        }
        EntityId entityId = new EntityId.UUID(rs.getObject(1, UUID.class));
        statement.getMoreResults();
        rs = statement.getResultSet();
        List<Event<?>> events = new ArrayList<>();
        while (rs.next()) {
          events.add(new Event<>(
              entityId.value(),
              rs.getInt(1), // EventNumber
              Mappers.eventTypeMapper(eventTypes).apply(UUID.fromString(rs.getString(2))), // Type
              rs.getObject(3, LocalDateTime.class), // Timestamp
              clock,
              rs.getBytes(4) // Data
          ));
        }
        rs.close();
        return new EventLog(entityModel, entityId, Collections.unmodifiableList(events));
      }
    })
    .single()
    .onErrorMap(
        e -> e instanceof SQLServerException f && f.getErrorCode() == 50000,
        _ -> new EntityGroupNotInitialised(entityModel, idModel, entityGroup)
    ).onErrorMap(
        SQLServerException.class,
        f -> new MssqlException(getClass().getSimpleName(), entityModel, f.getMessage())
    );
  }

}
