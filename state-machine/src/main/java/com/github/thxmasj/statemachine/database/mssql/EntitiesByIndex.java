package com.github.thxmasj.statemachine.database.mssql;

import com.github.thxmasj.statemachine.EntityId;
import com.github.thxmasj.statemachine.EntityModel;
import com.github.thxmasj.statemachine.Event;
import com.github.thxmasj.statemachine.EventLog;
import com.github.thxmasj.statemachine.EventType;
import com.github.thxmasj.statemachine.IndexEntityModel;
import com.github.thxmasj.statemachine.database.Parameter;
import com.github.thxmasj.statemachine.database.Row;
import com.github.thxmasj.statemachine.database.jdbc.JDBCRow;
import java.sql.ResultSet;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import static com.github.thxmasj.statemachine.database.jdbc.PreparedStatementSupport.prepare;

public class EntitiesByIndex {

  private final DataSource dataSource;
  private final BiFunction<EntityId, Row, Event<?>> eventMapper;
  private final Map<UUID, EntityModel> entityModels;
  private final String sql;

  public EntitiesByIndex(
      DataSource dataSource,
      List<EntityModel> entityModels,
      String schemaName,
      List<EventType<?, ?>> eventTypes,
      Clock clock
  ) {
    this.dataSource = dataSource;
    this.eventMapper = Mappers.eventMapper(eventTypes, clock);
    this.entityModels = entityModels.stream().collect(Collectors.toMap(EntityModel::id, Function.identity()));
    this.sql = String.format(
        """
        SELECT
          e.EntityId,
          ent.EntityModelId,
          e.EventNumber,
          e.Type,
          e.Timestamp,
          e.Data
        FROM [{schema}].Event e
          INNER JOIN [{schema}].[Entity] ent ON ent.EntityId = e.EntityId
          INNER JOIN [{schema}].IndexEvent ie ON e.EntityId=ie.EntityId
        WHERE ie.Data=:value
        ORDER BY e.EntityId, e.EventNumber;
        """
            .replace("{schema}", schemaName)
    );
  }

  public <T> Flux<EventLog> execute(IndexEntityModel<T> model, T key) {
    return Mono.fromCallable(() -> {
          try (
              var connection = dataSource.getConnection();
              var statement = prepare(sql, Map.of("value", new Parameter<>(byte[].class, model.marshal(key))), connection);
          ) {
            statement.execute();
            ResultSet rs = statement.getResultSet();
            Map<UUID, List<Event<?>>> eventsByEntityId = new LinkedHashMap<>();
            Map<UUID, UUID> entityModelIdByEntityId = new HashMap<>();
            while (rs.next()) {
              UUID entityId = rs.getObject("EntityId", UUID.class);
              UUID entityModelId = rs.getObject("EntityModelId", UUID.class);
              entityModelIdByEntityId.putIfAbsent(entityId, entityModelId);
              eventsByEntityId.computeIfAbsent(entityId, _ -> new ArrayList<>()).add(eventMapper.apply(new EntityId.UUID(entityId), new JDBCRow(rs)));
            }
            rs.close();
            List<EventLog> eventLogs = new ArrayList<>();
            for (var entry : eventsByEntityId.entrySet()) {
              UUID modelId = entityModelIdByEntityId.get(entry.getKey());
              EntityModel entityModel = modelId != null ? entityModels.get(modelId) : null;
              eventLogs.add(new EventLog(entityModel, new EntityId.UUID(entry.getKey()), List.of(), Collections.unmodifiableList(entry.getValue())));
            }
            return eventLogs;
          }
        }).flatMapMany(l -> Flux.fromIterable(l));
  }

}
