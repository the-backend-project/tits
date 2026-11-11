package com.github.thxmasj.backend.core.database.mssql;

import static com.github.thxmasj.backend.core.database.jdbc.PreparedStatementSupport.nextResultSet;
import static com.github.thxmasj.backend.core.database.jdbc.PreparedStatementSupport.prepare;
import static java.util.function.Function.identity;
import static java.util.stream.Collectors.toMap;

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

/// Events of the entity having an id, found by the id event of its id entity
public class EventsByIdEvent {

  private final DataSource dataSource;
  private final Map<UUID, EntityModel> entityModels;
  private final Map<EntityModel, List<EventType<?, ?>>> eventTypesByEntityModel;
  private final Clock clock;
  private final String sql;

  public EventsByIdEvent(
      DataSource dataSource,
      Map<EntityModel, List<EventType<?, ?>>> eventTypesByEntityModel,
      String schemaName,
      Clock clock
  ) {
    this(
        dataSource,
        eventTypesByEntityModel,
        clock,
        """
        SET NOCOUNT ON;
        DECLARE @entityId UNIQUEIDENTIFIER, @entityModelId UNIQUEIDENTIFIER;

        SELECT TOP(1) @entityId=e.EntityId, @entityModelId=e.EntityModelId
        FROM [{schema}].[IdEvent] ie WITH (INDEX(ixType_Data))
          INNER JOIN [{schema}].[Entity] e ON e.EntityId = ie.EntityId
        WHERE ie.Type=:type AND ie.Data=:data;

        SELECT @entityId AS EntityId, @entityModelId AS EntityModelId;

        SELECT EventNumber, Type, Timestamp, Data
        FROM [{schema}].[Event] WITH (INDEX(pkEvent))
        WHERE EntityId=@entityId
        ORDER BY EventNumber;
        """.replace("{schema}", schemaName)
    );
  }

  /// @param sql the SQL selecting a row with the entity id and entity model id, which are null if no entity has the id,
  ///            and then the events of the entity, in the dialect of the database
  protected EventsByIdEvent(
      DataSource dataSource,
      Map<EntityModel, List<EventType<?, ?>>> eventTypesByEntityModel,
      Clock clock,
      String sql
  ) {
    this.dataSource = dataSource;
    this.entityModels = eventTypesByEntityModel.keySet().stream().collect(toMap(EntityModel::id, identity()));
    this.eventTypesByEntityModel = eventTypesByEntityModel;
    this.clock = clock;
    this.sql = sql;
  }

  /// Completes empty if no entity has the id
  public Mono<EventLog> execute(EventType<?, ?> type, byte[] id) {
    return Mono.fromCallable(() -> {
      try (
          var connection = dataSource.getConnection();
          var statement = prepare(
              sql,
              Map.of(
                  "type", new Parameter<>(UUID.class, type.id()),
                  "data", new Parameter<>(byte[].class, id)
              ),
              connection
          )
      ) {
        ResultSet rs = nextResultSet(statement, statement.execute());
        rs.next();
        UUID entityId = rs.getObject("EntityId", UUID.class);
        if (entityId == null)
          return null;
        UUID modelId = rs.getObject("EntityModelId", UUID.class);
        EntityModel model = entityModels.get(modelId);
        if (model == null)
          throw new IllegalStateException("Unknown entity model " + modelId + " of entity having id of type " + type.name());
        rs = nextResultSet(statement, statement.getMoreResults());
        var eventMapper = Mappers.eventMapper(eventTypesByEntityModel.get(model), clock);
        List<Event<?>> events = new ArrayList<>();
        while (rs.next()) {
          try {
            events.add(eventMapper.apply(new EntityId.UUID(entityId), new JDBCRow(rs)));
          } catch (MappingFailure e) {
            throw new MappingFailure("EventsByIdEvent: Failed to map row to event for entity model " + model.name(), e);
          }
        }
        return new EventLog(model, new EntityId.UUID(entityId), Collections.unmodifiableList(events));
      }
    });
  }

}
