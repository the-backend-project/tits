package com.github.thxmasj.statemachine.database.mssql;

import com.github.thxmasj.statemachine.DelaySpecification;
import com.github.thxmasj.statemachine.EntityModel;
import com.github.thxmasj.statemachine.OutboxElement;
import com.github.thxmasj.statemachine.Subscriber;
import com.github.thxmasj.statemachine.database.Client;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import reactor.core.publisher.Flux;

public class ProcessBackedOff {

  private final Client databaseClient;
  private final Map<EntityModel, Map<Subscriber, String>> sqls;
  private final String schemaName;
  private final Clock clock;
  private final DelaySpecification backoff;

  public ProcessBackedOff(Client databaseClient, List<EntityModel> entityModels, String schemaName, Clock clock, DelaySpecification backoff) {
    this.databaseClient = databaseClient;
    this.schemaName = schemaName;
    this.clock = clock;
    this.backoff = backoff;
    this.sqls = new HashMap<>();
    for (var entityModel : entityModels) {
      sqls.put(entityModel, new HashMap<>());
      var names = new SchemaNames(schemaName, entityModel);
      var q = names.qualifiedNames();
      for (var subscriber : entityModel.subscribers()) {
        // NB: The POWER expression is limited to avoid arithmetic overflow
        var sql =
            """
            DECLARE @updated TABLE (
              ElementId BINARY(8),
              OutboxElementId BIGINT,
              EntityId UNIQUEIDENTIFIER,
              EventNumber SMALLINT,
              Guaranteed BIT,
              Data VARCHAR(MAX),
              CorrelationId VARCHAR(36),
              EnqueuedAt DATETIME2,
              Attempt INT,
              NextAttemptAt DATETIME2
            );

            WITH rows AS (
              SELECT TOP (1) * FROM {processingTable}
              WITH (INDEX([ixNextAttemptAt]))
              WHERE NextAttemptAt < :now
              ORDER BY NextAttemptAt ASC
            )
            UPDATE rows SET
              NextAttemptAt=DATEADD(
                      millisecond,
                      1000 * (SELECT MIN(i) FROM (VALUES
                        (:maximumBackoff),
                        (:minimumBackoff*POWER(:backoffPowerBase, (SELECT MIN(j) FROM (VALUES (Attempt), (50)) as N(j))))
                      ) AS T(i)),
                      :now
                    ),
              Attempt=Attempt+1
            OUTPUT
              inserted.ElementId,
              inserted.OutboxElementId,
              inserted.EntityId,
              inserted.EventNumber,
              inserted.Guaranteed,
              inserted.Data,
              inserted.CorrelationId,
              inserted.EnqueuedAt,
              inserted.Attempt,
              inserted.NextAttemptAt
            INTO @updated
            WHERE NextAttemptAt < :now
  
            SELECT
              ElementId,
              OutboxElementId,
              EntityId,
              EventNumber,
              Guaranteed,
              Data,
              CorrelationId,
              EnqueuedAt,
              Attempt,
              NextAttemptAt
            FROM @updated
            """.replace("{processingTable}", q.processingTable(subscriber))
            ;
        sqls.get(entityModel).put(subscriber, sql);
      }
    }
  }

  public Flux<OutboxElement> execute(LocalDateTime now, EntityModel entityModel, Subscriber subscriber) {
    String sql = sqls.get(entityModel).get(subscriber);
    return databaseClient.sql(sql)
        .name("ProcessBackedOff")
        .bind("now", now)
        .bind("maximumBackoff", backoff.maximum().toSeconds())
        .bind("minimumBackoff", backoff.minimum().toSeconds())
        .bind("backoffPowerBase", backoff.powerBase())
        .map(Mappers.queueElementMapper(entityModel, new SchemaNames(schemaName, entityModel), clock, subscriber, now))
        .all();
  }

}
