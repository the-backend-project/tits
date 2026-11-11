package com.github.thxmasj.backend.core.database;

import com.github.thxmasj.backend.core.DelayedTrigger;
import com.github.thxmasj.backend.core.DelaySpecification;
import com.github.thxmasj.backend.core.EntityId;
import com.github.thxmasj.backend.core.EntityModel;
import com.github.thxmasj.backend.core.EventType;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import reactor.core.publisher.Mono;

public class NextDelayedTrigger {

  private final Client databaseClient;
  private final Clock clock;
  private final String sql;
  private final Map<UUID, EntityModel> entityModels;
  private final Function<UUID, EventType<?, ?>> eventTypeMapper;

  /// @param sql the SQL selecting the delayed trigger with the earliest passed deadline, if any, and postponing its
  ///            deadline to its next attempt
  public NextDelayedTrigger(
      Client databaseClient,
      String sql,
      List<EventType<?, ?>> eventTypes,
      Clock clock,
      List<EntityModel> entityModels
  ) {
    this.databaseClient = databaseClient;
    this.eventTypeMapper = Mappers.eventTypeMapper(eventTypes);
    this.clock = clock;
    this.entityModels = entityModels.stream().collect(Collectors.toMap(EntityModel::id, Function.identity()));
    this.sql = sql;
  }

  public Mono<? extends DelayedTrigger<?>> execute(DelaySpecification backoff) {
    return databaseClient.sql(sql)
        .name("NextDelayedTrigger")
        .bind("now", ZonedDateTime.now(clock).toLocalDateTime())
        .bind("maximumBackoff", backoff.maximum().toSeconds())
        .bind("minimumBackoff", backoff.minimum().toSeconds())
        .bind("backoffPowerBase", backoff.powerBase())
        .map(row -> {
          EventType<?, ?> eventType = eventTypeMapper.apply(row.get("Type", UUID.class));
          byte[] rawData = row.get("Data", byte[].class);
          return create(
                  new EntityId.UUID(row.get("EntityId", UUID.class)),
                  entityModels.get(row.get("EntityModelId", UUID.class)),
                  row.get("EventNumber", Integer.class),
                  eventType,
                  rawData,
                  ZonedDateTime.of(row.get("NextAttemptAt", LocalDateTime.class), clock.getZone()),
                  row.get("CorrelationId", String.class)
          );
        })
        .one();
  }

  private <T> DelayedTrigger<T> create(
          EntityId entityId,
          EntityModel model,
          int eventNumber,
          EventType<T, ?> eventType,
          byte[] data,
          ZonedDateTime nextAttemptAt,
          String correlationId
  ) {
    return new DelayedTrigger<>(
        entityId,
        model,
        eventNumber,
        eventType,
        data != null ? eventType.inputDataType().unmarshal(data) : null,
        nextAttemptAt,
        correlationId
    );
  }

}
