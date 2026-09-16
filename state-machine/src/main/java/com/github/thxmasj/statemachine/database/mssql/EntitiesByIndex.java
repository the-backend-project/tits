package com.github.thxmasj.statemachine.database.mssql;

import com.github.thxmasj.statemachine.EntityId;
import com.github.thxmasj.statemachine.Event;
import com.github.thxmasj.statemachine.EventLog;
import com.github.thxmasj.statemachine.IndexEntityModel;
import com.github.thxmasj.statemachine.database.Row;
import com.github.thxmasj.statemachine.database.jdbc.JDBCRow;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiFunction;
import javax.sql.DataSource;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public class EntitiesByIndex {

  private final DataSource dataSource;
  private final BiFunction<EntityId, Row, Event<?>> eventMapper;
  private final String sql;

  public EntitiesByIndex(
      DataSource dataSource,
      String schemaName,
      BiFunction<EntityId, Row, Event<?>> eventMapper
  ) {
    this.dataSource = dataSource;
    this.eventMapper = eventMapper;
    this.sql = String.format(
        """
        SELECT
          e.EntityId,
          e.EventNumber,
          e.Type,
          e.Timestamp,
          e.Data
        FROM [{schema}].Event e
          INNER JOIN [{schema}].IndexEvent ie ON e.EntityId=ie.EntityId
        WHERE ie.Data=@value
        ORDER BY e.EntityId, e.EventNumber;
        """
            .replace("{schema}", schemaName)
    );
  }

  public <T> Flux<EventLog> execute(IndexEntityModel<T> model, T key) {
    return Mono.fromCallable(() -> {
          try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(sql)) {
            statement.setString(1, model.marshal(key));
            statement.execute();
            ResultSet rs = statement.getResultSet();
            Map<UUID, List<Event<?>>> eventsByEntityId = new HashMap<>();
            while (rs.next()) {
              UUID entityId = rs.getObject("EntityId", UUID.class);
              eventsByEntityId.computeIfAbsent(entityId, _ -> new ArrayList<>()).add(eventMapper.apply(new EntityId.UUID(entityId), new JDBCRow(rs)));
            }
            rs.close();
            List<EventLog> eventLogs = new ArrayList<>();
            for (var entry : eventsByEntityId.entrySet()) {
              eventLogs.add(new EventLog(null, new EntityId.UUID(entry.getKey()), List.of(), Collections.unmodifiableList(entry.getValue())));
            }
            return eventLogs;
          }
        }).flatMapMany(l -> Flux.fromIterable(l));
  }

}
