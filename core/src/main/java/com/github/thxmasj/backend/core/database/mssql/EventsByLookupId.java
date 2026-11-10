package com.github.thxmasj.backend.core.database.mssql;

import static com.github.thxmasj.backend.core.database.jdbc.PreparedStatementSupport.prepare;
import static java.util.Optional.ofNullable;

import com.github.thxmasj.backend.core.EntityId;
import com.github.thxmasj.backend.core.EntityModel;
import com.github.thxmasj.backend.core.Event;
import com.github.thxmasj.backend.core.EventLog;
import com.github.thxmasj.backend.core.EventType;
import com.github.thxmasj.backend.core.SecondaryId;
import com.github.thxmasj.backend.core.database.MappingFailure;
import com.github.thxmasj.backend.core.database.Parameter;
import com.github.thxmasj.backend.core.database.UnknownEntity;
import com.github.thxmasj.backend.core.database.jdbc.JDBCRow;
import com.microsoft.sqlserver.jdbc.SQLServerException;
import java.sql.ResultSet;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import reactor.core.publisher.Mono;

/// Events of the entity having a secondary id, found by the id event of the id entity sharing its entity id
public class EventsByLookupId {

  private final DataSource dataSource;
  private final String sql;
  private final Clock clock;

  public EventsByLookupId(
      DataSource dataSource,
      String schemaName,
      Clock clock
  ) {
    this.dataSource = dataSource;
    this.clock = clock;
    this.sql =
        """
        DECLARE @entityId UNIQUEIDENTIFIER;

        SELECT TOP(1) @entityId=EntityId
        FROM [{schema}].[IdEvent] WITH (INDEX(ixType_Data))
        WHERE Type=:type AND Data=:data;

        IF @@ROWCOUNT < 1
          THROW 50000, 'Unknown entity', 1

        SELECT @entityId;

        SELECT EventNumber, Type, Timestamp, Data
        FROM [{schema}].[Event] WITH (INDEX(pkEvent))
        WHERE EntityId=@entityId
        ORDER BY EventNumber;
        """.replace("{schema}", schemaName);
  }

  public Mono<EventLog> execute(EntityModel entityModel, List<EventType<?, ?>> eventTypes, SecondaryId<?> secondaryId) {
    return Mono.fromCallable(() -> {
          try (
              var connection = dataSource.getConnection();
              var statement = prepare(
                  sql,
                  Map.of(
                      "type", new Parameter<>(UUID.class, secondaryId.model().id()),
                      "data", new Parameter<>(byte[].class, secondaryId.marshalledData())
                  ),
                  connection
              )
          ) {
            statement.execute();
            ResultSet rs = statement.getResultSet();
            if (!rs.next()) {
              throw new IllegalStateException("No result set with entity id. Shouldn't happen.");
            }
            EntityId entityId = ofNullable(rs.getObject(1, UUID.class)).map(EntityId.UUID::new).orElse(null);
            if (entityId == null)
              return null; // Yields Mono.empty
            statement.getMoreResults();
            rs = statement.getResultSet();
            List<Event<?>> events = new ArrayList<>();
            while (rs.next()) {
              try {
                events.add(Mappers.eventMapper(eventTypes, clock).apply(entityId, new JDBCRow(rs)));
              } catch (MappingFailure e) {
                throw new MappingFailure("EventsBySecondaryId: Failed to map row to event for entity model " + entityModel.name(), e);
              }
            }
            rs.close();
            return new EventLog(entityModel, entityId, Collections.unmodifiableList(events));
          }
        })
        .onErrorMap(
            e -> e instanceof SQLServerException f && f.getErrorCode() == 50000,
            _ -> new UnknownEntity(entityModel, secondaryId)
        )
        ;
  }

}
