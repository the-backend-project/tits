package com.github.thxmasj.statemachine.database.mssql;

import com.github.thxmasj.statemachine.DelaySpecification;
import com.github.thxmasj.statemachine.EntityModel;
import com.github.thxmasj.statemachine.OutboxElement;
import com.github.thxmasj.statemachine.Subscriber;
import com.github.thxmasj.statemachine.database.Client;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import reactor.core.publisher.Flux;

public class ProcessBackedOff {

  private final List<EntityModel> entityModels;
  private final Client databaseClient;
  private final String sql;
  private final Clock clock;
  private final DelaySpecification backoff;

  public ProcessBackedOff(Client databaseClient, List<EntityModel> entityModels, String schemaName, Clock clock, DelaySpecification backoff) {
    this.entityModels = entityModels;
    this.databaseClient = databaseClient;
    this.clock = clock;
    this.backoff = backoff;
    // NB: The POWER expression is limited to avoid arithmetic overflow
    this.sql =
        """
        DECLARE @updated TABLE (
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
        );

        WITH rows AS (
          SELECT TOP (1) * FROM [{schema}].[OutboxQueueProcessing]
          WITH (INDEX([ixNextAttemptAt]))
          WHERE NextAttemptAt < :now AND SubscriberId=:subscriberId
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
        INTO @updated
        WHERE NextAttemptAt < :now
  
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
        FROM @updated
        """.replace("{schema}", schemaName);
  }

  public Flux<OutboxElement> execute(LocalDateTime now, Subscriber subscriber) {
    return databaseClient.sql(sql)
        .name("ProcessBackedOff")
        .bind("now", now)
        .bind("maximumBackoff", backoff.maximum().toSeconds())
        .bind("minimumBackoff", backoff.minimum().toSeconds())
        .bind("backoffPowerBase", backoff.powerBase())
        .bind("subscriberId", subscriber.id())
        .map(Mappers.queueElementMapper(entityModels, clock, subscriber, now))
        .all()
        .doOnError(Throwable::printStackTrace)
        ;
  }

}
