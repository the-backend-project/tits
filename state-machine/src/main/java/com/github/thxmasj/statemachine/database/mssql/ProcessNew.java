package com.github.thxmasj.statemachine.database.mssql;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import com.github.thxmasj.statemachine.DelaySpecification;
import com.github.thxmasj.statemachine.EntityModel;
import com.github.thxmasj.statemachine.OutboxWorker.Simulation;
import com.github.thxmasj.statemachine.OutboxElement;
import com.github.thxmasj.statemachine.Subscriber;
import com.github.thxmasj.statemachine.database.Client;
import com.github.thxmasj.statemachine.database.Client.PrimaryKeyConstraintViolation;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public class ProcessNew {

  private final List<EntityModel> entityModels;
  private final Client databaseClient;
  private final Map<EntityModel, Map<Subscriber, String>> sqls;
  private final String schemaName;
  private final Clock clock;
  private final DelaySpecification backoff;

  public ProcessNew(Client databaseClient, List<EntityModel> entityModels, String schemaName, Clock clock, DelaySpecification backoff) {
    this.entityModels = entityModels;
    this.databaseClient = databaseClient;
    this.schemaName = schemaName;
    this.clock = clock;
    this.backoff = backoff;
    this.sqls = new HashMap<>();
    for (var entityModel : entityModels) {
      sqls.put(entityModel, new HashMap<>());
      var names = new SchemaNames(schemaName, entityModel);
      var q = names.qualifiedNames();
      for (var subscriber : entityModel.subscribers().stream().toList()) {
        var sql =
            """
              DECLARE @selected TABLE (
                ElementId BINARY(8),
                OutboxElementId BIGINT,
                EntityId UNIQUEIDENTIFIER,
                EventNumber SMALLINT,
                CreatorId UNIQUEIDENTIFIER,
                Guaranteed BIT,
                Data VARCHAR(MAX),
                CorrelationId VARCHAR(36),
                EnqueuedAt DATETIME2,
                Attempt INT,
                NextAttemptAt DATETIME2
              )
            
              INSERT TOP (1) INTO {processingTable} (
                ElementId,
                OutboxElementId,
                EntityId,
                EventNumber,
                CreatorId,
                Guaranteed,
                Data,
                CorrelationId,
                EnqueuedAt,
                Attempt,
                NextAttemptAt
              )
              OUTPUT
                inserted.ElementId,
                inserted.OutboxElementId,
                inserted.EntityId,
                inserted.EventNumber,
                inserted.CreatorId,
                inserted.Guaranteed,
                inserted.Data,
                inserted.CorrelationId,
                inserted.EnqueuedAt,
                inserted.Attempt,
                inserted.NextAttemptAt
              INTO @selected
              SELECT
                q.ElementId,
                q.OutboxElementId,
                q.EntityId,
                q.EventNumber,
                q.CreatorId,
                q.Guaranteed,
                d.Data,
                q.CorrelationId,
                q.Timestamp,
                1,
                (DATEADD(millisecond, :minimumBackoff*1000, :now))
              FROM {queueTable} q WITH (INDEX([ixEntityId]))
                LEFT JOIN {outboxTable} d WITH (INDEX([{outboxTablePK}]))
                ON q.OutboxElementId=d.Id
              WHERE q.Guaranteed=1
                AND q.EntityId NOT IN (SELECT EntityId FROM {dlqTable} WITH (INDEX([{dlqTablePK}])))
                AND q.EntityId NOT IN (SELECT EntityId FROM {processingTable} WITH (INDEX([{processingTablePK}])))
                {parentEntityFilter}
            ORDER BY ElementId;
            
            SELECT
              ElementId,
              OutboxElementId,
              EntityId,
              EventNumber,
              CreatorId,
              Guaranteed,
              Data,
              CorrelationId,
              EnqueuedAt,
              Attempt,
              NextAttemptAt
            FROM @selected
            """.replace("{processingTable}", q.processingTable(subscriber))
                .replace("{processingTablePK}", names.notificationQueueProcessingTablePrimaryKeyName(subscriber))
                .replace("{dlqTable}", q.dlqTable(subscriber))
                .replace("{dlqTablePK}", names.dlqTablePrimaryKeyName(subscriber))
                .replace("{queueTable}", q.queueTable(subscriber))
                .replace("{outboxTable}", q.outboxTable(subscriber))
                .replace("{outboxTablePK}", names.outboxTablePrimaryKeyName(subscriber))
                .replace("{parentEntityFilter}", childEntity(entityModel) == null ? "" :
                    String.format(
                        "AND q.EntityId NOT IN (SELECT ParentEntityId FROM %s WHERE ParentEntityId IS NOT NULL)",
                        new SchemaNames(schemaName, childEntity(entityModel)).qualifiedNames().queueTable(subscriber)
                    )
                );
        sqls.get(entityModel).put(subscriber, sql);
      }
    }
  }

  private String sql(EntityModel entityModel, Subscriber subscriber) {
    Map<Subscriber, String> sqlsForEntityModel = sqls.get(entityModel);
    if (sqlsForEntityModel == null) throw new IllegalStateException("No SQL for " + entityModel.name());
    String sql = sqlsForEntityModel.get(subscriber);
    if (sql == null) throw new IllegalStateException("No SQL for " + subscriber.name());
    return sql;
  }

  public Flux<OutboxElement> execute(LocalDateTime now, EntityModel entityModel, Subscriber subscriber) {
    String sql = sql(entityModel, subscriber);
    return databaseClient.sql(sql)
        .name("ProcessNew")
        .bind("now", now)
        .bind("minimumBackoff", backoff.minimum().toSeconds())
        .map(Mappers.queueElementMapper(entityModel, clock, subscriber, now))
        .all()
        .switchIfEmpty(raceSimulationIfTriggered(entityModel, subscriber));
  }

  private EntityModel childEntity(EntityModel thisModel) {
    return entityModels.stream()
        .filter(e -> thisModel.equals(e.parentEntity()))
        .findFirst()
        .orElse(null);
  }

  private Mono<OutboxElement> raceSimulationIfTriggered(EntityModel model, Subscriber subscriber) {
    return Mono.deferContextual(ctx -> ctx
        .getOrEmpty(Simulation.Race)
        .map(_ -> Mono.<OutboxElement>error(new PrimaryKeyConstraintViolation("ProcessNew", model, new SchemaNames(schemaName, model).processingTableName(subscriber))))
        .orElse(Mono.empty())
    );
  }

}
