package com.github.thxmasj.backend.core.internal.database.sql;

import com.github.thxmasj.backend.core.EntityModel;
import com.github.thxmasj.backend.core.EventType;
import com.github.thxmasj.backend.core.IdEntityModel;
import com.github.thxmasj.backend.core.IndexEntityModel;
import com.github.thxmasj.backend.core.database.DatabaseClient;
import com.github.thxmasj.backend.core.internal.ResolvedModels;
import com.github.thxmasj.backend.core.internal.database.ConditionalTrigger;
import com.github.thxmasj.backend.core.internal.database.TestStorage;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.function.Function;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/// The queries and updates of the test API. They quote identifiers and use OFFSET/FETCH, so that they work in both SQL
/// Server and PostgreSQL.
public class SqlTestStorage implements TestStorage {

  private final DatabaseClient client;
  private final String schema;
  private final Clock clock;
  private final ResolvedModels models;
  private final Function<UUID, EventType<?, ?>> eventTypeMapper;
  private final String conditionalTriggersSql;
  /// Maps the rows of the conditional triggers
  private final SqlConditionalTriggers conditionalTriggers;
  /// Whether a delayed trigger is still pending, i.e. its event number follows the entity's last event
  private final String pending;

  public SqlTestStorage(DatabaseClient client, ResolvedModels models, String schemaName, Clock clock) {
    this.client = client;
    this.schema = schemaName;
    this.clock = clock;
    this.models = models;
    this.eventTypeMapper = Mappers.eventTypeMapper(models.eventTypes());
    this.conditionalTriggersSql =
        """
        SELECT Id, EntityId, EntityModelId, TriggerEntityId, TriggerEventNumber, Type, Data, Timestamp, LastTypes, ReferenceType, IndexKey, CorrelationId
        FROM "{schema}"."ConditionalTrigger"
        WHERE EntityId=:entityId
        ORDER BY Timestamp, Id
        """.replace("{schema}", schema);
    this.conditionalTriggers = new SqlConditionalTriggers(client, conditionalTriggersSql, models.eventTypesByEntityModel(), clock);
    this.pending = "EventNumber=(SELECT COALESCE(MAX(EventNumber), 0) + 1 FROM \"{schema}\".\"Event\" WHERE EntityId=:entityId)";
  }

  @Override
  public Mono<EntityModel> entityModel(UUID entityId) {
    return client.sql("SELECT EntityModelId FROM \"{schema}\".\"Entity\" WHERE EntityId=:entityId".replace("{schema}", schema))
        .name("TestApi.EntityModel")
        .bind("entityId", entityId)
        .map(row -> entityModelOf(row.get("EntityModelId", UUID.class)))
        .one();
  }

  @Override
  public Mono<byte[]> lastId(UUID entityId, EventType<?, ?> idType) {
    return client.sql(
            """
            SELECT Data FROM "{schema}"."IdEvent"
            WHERE EntityId=:entityId AND Type=:type
            ORDER BY EventNumber DESC
            OFFSET 0 ROWS FETCH NEXT 1 ROWS ONLY
            """.replace("{schema}", schema)
        )
        .name("TestApi.Id")
        .bind("entityId", entityId)
        .bind("type", idType.id())
        .map(row -> row.get("Data", byte[].class))
        .one();
  }

  @Override
  public Mono<Integer> nextEventNumber(EntityModel entityModel, UUID entityId) {
    String table = switch (entityModel) {
      case IdEntityModel _ -> "IdEvent";
      case IndexEntityModel _ -> "IndexEvent";
      default -> "Event";
    };
    return client.sql(
            "SELECT COALESCE(MAX(EventNumber), 0) + 1 AS EventNumber FROM \"{schema}\".\"{table}\" WHERE EntityId=:entityId"
                .replace("{schema}", schema)
                .replace("{table}", table)
        )
        .name("TestApi.NextEventNumber")
        .bind("entityId", entityId)
        .map(row -> row.get("EventNumber", Integer.class))
        .one();
  }

  @Override
  public Mono<PendingDelayedTrigger> delayedTrigger(UUID entityId) {
    return client.sql(
            """
            SELECT EntityModelId, EventNumber, Type, Deadline, Attempt
            FROM "{schema}"."DelayedTrigger"
            WHERE EntityId=:entityId AND {pending}
            """.replace("{pending}", pending).replace("{schema}", schema)
        )
        .name("TestApi.DelayedTrigger")
        .bind("entityId", entityId)
        .map(row -> new PendingDelayedTrigger(
            entityModelOf(row.get("EntityModelId", UUID.class)),
            row.get("EventNumber", Integer.class),
            eventType(row.get("Type", UUID.class)),
            row.get("Deadline", LocalDateTime.class),
            row.get("Attempt", Integer.class)
        ))
        .one();
  }

  @Override
  public Flux<ConditionalTrigger<?>> conditionalTriggers(UUID entityId) {
    return client.sql(conditionalTriggersSql)
        .name("TestApi.ConditionalTriggers")
        .bind("entityId", entityId)
        .<ConditionalTrigger<?>>map(conditionalTriggers::map)
        .all();
  }

  @Override
  public Mono<Boolean> expireDelayedTrigger(UUID entityId) {
    return client.sql(
            "UPDATE \"{schema}\".\"DelayedTrigger\" SET Deadline=:now WHERE EntityId=:entityId AND {pending}"
                .replace("{pending}", pending)
                .replace("{schema}", schema)
        )
        .name("TestApi.ExpireDelayedTrigger")
        .bind("now", LocalDateTime.now(clock))
        .bind("entityId", entityId)
        .update()
        .map(count -> count > 0);
  }

  @Override
  public Mono<Integer> deleteDelayedTriggers() {
    return client.sql("DELETE FROM \"{schema}\".\"DelayedTrigger\"".replace("{schema}", schema))
        .name("TestApi.DeleteDelayedTriggers")
        .update();
  }

  private EntityModel entityModelOf(UUID id) {
    EntityModel entityModel = models.entityModelsById().get(id);
    if (entityModel == null)
      throw new IllegalStateException("Unknown entity model " + id + ". Is it in the entity models given to the test API?");
    return entityModel;
  }

  private EventType<?, ?> eventType(UUID id) {
    EventType<?, ?> eventType = eventTypeMapper.apply(id);
    if (eventType == null)
      throw new IllegalStateException("Unknown event type " + id + ". Is it in the entity models given to the test API?");
    return eventType;
  }

}
