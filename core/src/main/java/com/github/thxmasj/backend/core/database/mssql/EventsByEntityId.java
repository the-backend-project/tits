package com.github.thxmasj.backend.core.database.mssql;

import static com.github.thxmasj.backend.core.database.jdbc.PreparedStatementSupport.prepare;
import static java.util.Objects.requireNonNull;

import com.github.thxmasj.backend.core.EntityId;
import com.github.thxmasj.backend.core.EntityModel;
import com.github.thxmasj.backend.core.Event;
import com.github.thxmasj.backend.core.EventLog;
import com.github.thxmasj.backend.core.EventType;
import com.github.thxmasj.backend.core.IdEntityModel;
import com.github.thxmasj.backend.core.IndexEntityModel;
import com.github.thxmasj.backend.core.database.MappingFailure;
import com.github.thxmasj.backend.core.database.Parameter;
import com.github.thxmasj.backend.core.database.UnknownEntity;
import com.github.thxmasj.backend.core.database.jdbc.JDBCRow;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import javax.sql.DataSource;
import reactor.core.publisher.Mono;

public class EventsByEntityId {

  private final DataSource dataSource;
  private final Map<EntityModel, String> sqls;
  private final Clock clock;

  public EventsByEntityId(
      DataSource dataSource,
      List<EntityModel> entityModels,
      String schemaName,
      Clock clock
  ) {
    this(
        dataSource,
        entityModels,
        table -> """
            SELECT EventNumber, Type, Timestamp, Data
            FROM [{schema}].[{table}] WITH (INDEX(pk{table}))
            WHERE EntityId=:entityId
            ORDER BY EventNumber;
            """.replace("{schema}", schemaName)
            .replace("{table}", table),
        clock
    );
  }

  /// @param sql the SQL selecting the events of an entity from a table, in the dialect of the database
  protected EventsByEntityId(
      DataSource dataSource,
      List<EntityModel> entityModels,
      Function<String, String> sql,
      Clock clock
  ) {
    this.dataSource = dataSource;
    this.clock = clock;
    this.sqls = new HashMap<>();
    for (var entityModel : entityModels) {
      String table = switch (entityModel) {
        case IdEntityModel _ -> "IdEvent";
        case IndexEntityModel _ -> "IndexEvent";
        default -> "Event";
      };
      this.sqls.put(entityModel, sql.apply(table));
    }
  }

  public Mono<EventLog> execute(EntityModel entityModel, List<EventType<?, ?>> eventTypes, EntityId entityId) {
    String sqlToPrepare = requireNonNull(sqls.get(entityModel), "Unknown model " + entityModel.name());
    return Mono.fromCallable(() -> {
      try (
          Connection connection = dataSource.getConnection();
          PreparedStatement statement = prepare(sqlToPrepare, Map.of("entityId", new Parameter<>(UUID.class, entityId.value())), connection)
      ) {
        statement.execute();
        ResultSet rs = statement.getResultSet();
        List<Event<?>> events = new ArrayList<>();
        while (rs.next()) {
          try {
            events.add(Mappers.eventMapper(eventTypes, clock).apply(entityId, new JDBCRow(rs)));
          } catch (MappingFailure e) {
            throw new MappingFailure("EventsByEntityId: Failed to map row to event for entity model " + entityModel.name(), e);
          }
        }
        rs.close();
        if (events.isEmpty())
          throw new UnknownEntity(entityModel, entityId);
        //System.out.println("Found event log for " + entityModel.name() + "/" + entityId.value() + ": " + events.stream().map(e -> e.typeName()).collect(joining(", ")));
        return new EventLog(entityModel, entityId, Collections.unmodifiableList(events));
      }
    });
  }

}
