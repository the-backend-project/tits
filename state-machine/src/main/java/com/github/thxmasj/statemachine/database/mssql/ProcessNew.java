package com.github.thxmasj.statemachine.database.mssql;

import com.github.thxmasj.statemachine.DelaySpecification;
import com.github.thxmasj.statemachine.EntityModel;
import com.github.thxmasj.statemachine.OutboxElement;
import com.github.thxmasj.statemachine.OutboxWorker.Simulation;
import com.github.thxmasj.statemachine.OutboxQueue;
import com.github.thxmasj.statemachine.database.Client;
import com.github.thxmasj.statemachine.database.Client.PrimaryKeyConstraintViolation;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public class ProcessNew {

  private final List<EntityModel> entityModels;
  private final Client databaseClient;
  private final Map<EntityModel, Map<OutboxQueue, String>> sqls;
  private final Clock clock;
  private final DelaySpecification backoff;

  public ProcessNew(Client databaseClient, List<EntityModel> entityModels, String schemaName, Clock clock, DelaySpecification backoff) {
    this.entityModels = entityModels;
    this.databaseClient = databaseClient;
    this.clock = clock;
    this.backoff = backoff;
    this.sqls = new HashMap<>();
    for (var entityModel : entityModels) {
      sqls.put(entityModel, new HashMap<>());
      for (var queue : entityModel.queues().stream().toList()) {
        var sql =
            """
              DECLARE @selected TABLE (
                ElementId BINARY(8),
                RequestId UNIQUEIDENTIFIER,
                EntityModelId UNIQUEIDENTIFIER,
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
            
              INSERT TOP (1) INTO [{schema}].[OutboxQueueProcessing] (
                ElementId,
                QueueId,
                EntityModelId,
                RequestId,
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
                inserted.RequestId,
                inserted.EntityModelId,
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
                q.QueueId,
                q.EntityModelId,
                q.RequestId,
                q.EntityId,
                q.EventNumber,
                q.CreatorId,
                q.Guaranteed,
                d.Data,
                q.CorrelationId,
                q.Timestamp,
                1,
                (DATEADD(millisecond, :minimumBackoff*1000, :now))
              FROM [{schema}].[OutboxQueue] q WITH (INDEX([ixEntityId]))
                LEFT JOIN [{schema}].[OutboxRequest] d WITH (INDEX([pkOutboxRequest]))
                ON q.RequestId=d.Id
              WHERE q.Guaranteed=1
                AND q.QueueId='{queueId}'
                AND q.EntityId NOT IN (SELECT EntityId FROM [{schema}].[OutboxDeadLetterQueue] WITH (INDEX([pkOutboxDeadLetterQueue])))
                AND q.EntityId NOT IN (SELECT EntityId FROM [{schema}].[OutboxQueueProcessing] WITH (INDEX([pkOutboxQueueProcessing])))
                {parentEntityFilter}
            ORDER BY ElementId;
            
            SELECT
              ElementId,
              RequestId,
              EntityModelId,
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
            """.replace("{schema}", schemaName)
                .replace("{queueId}", queue.id().toString())
                .replace("{parentEntityFilter}", childEntity(entityModel) == null ? "" :
                        "AND q.EntityId NOT IN (SELECT ParentEntityId FROM [{schema}].[OutboxQueue] WHERE ParentEntityId IS NOT NULL)".replace("{schema}", schemaName)
                );
        sqls.get(entityModel).put(queue, sql);
      }
    }
  }

  private String sql(EntityModel entityModel, OutboxQueue queue) {
    Map<OutboxQueue, String> sqlsForEntityModel = sqls.get(entityModel);
    if (sqlsForEntityModel == null) throw new IllegalStateException("No SQL for " + entityModel.name());
    String sql = sqlsForEntityModel.get(queue);
    if (sql == null) throw new IllegalStateException("No SQL for " + queue.name());
    return sql;
  }

  public Flux<OutboxElement> execute(LocalDateTime now, EntityModel entityModel, OutboxQueue queue) {
    String sql = sql(entityModel, queue);
    return databaseClient.sql(sql)
        .name("ProcessNew")
        .bind("now", now)
        .bind("minimumBackoff", backoff.minimum().toSeconds())
        .map(Mappers.queueElementMapper(entityModels, clock, queue, now))
        .all()
        .switchIfEmpty(raceSimulationIfTriggered(entityModel));
  }

  private EntityModel childEntity(EntityModel thisModel) {
    return entityModels.stream()
        .filter(e -> thisModel.equals(e.parentEntity()))
        .findFirst()
        .orElse(null);
  }

  private Mono<OutboxElement> raceSimulationIfTriggered(EntityModel model) {
    return Mono.deferContextual(ctx -> ctx
        .getOrEmpty(Simulation.Race)
        .map(_ -> Mono.<OutboxElement>error(new PrimaryKeyConstraintViolation("ProcessNew", model, "OutboxQueueProcessing")))
        .orElse(Mono.empty())
    );
  }

}
