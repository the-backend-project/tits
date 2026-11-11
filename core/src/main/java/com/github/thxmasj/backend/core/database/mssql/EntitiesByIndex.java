package com.github.thxmasj.backend.core.database.mssql;

import static com.github.thxmasj.backend.core.database.jdbc.PreparedStatementSupport.prepare;
import static java.util.Collections.unmodifiableList;

import com.github.thxmasj.backend.core.DataType;
import com.github.thxmasj.backend.core.EntityId;
import com.github.thxmasj.backend.core.EntityModel;
import com.github.thxmasj.backend.core.Event;
import com.github.thxmasj.backend.core.EventLog;
import com.github.thxmasj.backend.core.EventType;
import com.github.thxmasj.backend.core.database.Parameter;
import com.github.thxmasj.backend.core.database.Row;
import com.github.thxmasj.backend.core.database.jdbc.JDBCRow;
import java.sql.ResultSet;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public class EntitiesByIndex {

  private final DataSource dataSource;
  private final Map<UUID, BiFunction<EntityId, Row, Event<?>>> eventMappers;
  private final Map<UUID, EntityModel> entityModels;
  private final String sql;

  /// Maps the events of all the entity models with the same event types. Entity models whose event types share ids but
  /// differ in data types, like outbox requests with different payloads, need
  /// [#EntitiesByIndex(DataSource, Map, String, Clock)].
  public EntitiesByIndex(
      DataSource dataSource,
      List<? extends EntityModel> entityModels,
      String schemaName,
      List<EventType<?, ?>> eventTypes,
      Clock clock
  ) {
    this(
        dataSource,
        entityModels.stream().collect(Collectors.toMap(Function.identity(), _ -> eventTypes)),
        schemaName,
        clock
    );
  }

  /// Maps the events of each entity model with its own event types
  public EntitiesByIndex(
      DataSource dataSource,
      Map<? extends EntityModel, List<EventType<?, ?>>> eventTypesByEntityModel,
      String schemaName,
      Clock clock
  ) {
    this.dataSource = dataSource;
    this.eventMappers = eventTypesByEntityModel.entrySet().stream()
        .collect(Collectors.toMap(e -> e.getKey().id(), e -> Mappers.eventMapper(e.getValue(), clock)));
    this.entityModels = eventTypesByEntityModel.keySet().stream().collect(Collectors.toMap(EntityModel::id, Function.identity()));
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
        WHERE ie.Data=:value AND ie.Type=:referenceType
        ORDER BY e.EntityId, e.EventNumber;
        """
            .replace("{schema}", schemaName)
    );
  }

  public Flux<EventLog> execute(UUID key, EventType<?, ?> referenceType) {
    return execute(DataType.uuid().marshal(key), referenceType);
  }

  /// @param key the marshalled index key
  public Flux<EventLog> execute(byte[] key, EventType<?, ?> referenceType) {
    return Mono.fromCallable(() -> {
          try (
              var connection = dataSource.getConnection();
              var statement = prepare(
                  sql,
                  Map.of(
                      "value", new Parameter<>(byte[].class, key),
                      "referenceType", new Parameter<>(UUID.class, referenceType.id())
                  ),
                  connection
              );
          ) {
            statement.execute();
            ResultSet rs = statement.getResultSet();
            Map<UUID, List<Event<?>>> eventsByEntityId = new LinkedHashMap<>();
            Map<UUID, UUID> entityModelIdByEntityId = new HashMap<>();
            while (rs.next()) {
              UUID entityId = rs.getObject("EntityId", UUID.class);
              UUID entityModelId = rs.getObject("EntityModelId", UUID.class);
              if (!entityModels.containsKey(entityModelId)) continue;
              entityModelIdByEntityId.putIfAbsent(entityId, entityModelId);
              eventsByEntityId.computeIfAbsent(entityId, _ -> new ArrayList<>()).add(eventMappers.get(entityModelId).apply(new EntityId.UUID(entityId), new JDBCRow(rs)));
            }
            rs.close();
            List<EventLog> eventLogs = new ArrayList<>();
            for (Entry<UUID, List<Event<?>>> entry : eventsByEntityId.entrySet()) {
              UUID modelId = entityModelIdByEntityId.get(entry.getKey());
              EntityModel entityModel = entityModels.get(modelId);
              eventLogs.add(new EventLog(entityModel, new EntityId.UUID(entry.getKey()), unmodifiableList(entry.getValue())));
            }
            return eventLogs;
          }
        }).flatMapMany(l -> Flux.fromIterable(l));
  }

}
