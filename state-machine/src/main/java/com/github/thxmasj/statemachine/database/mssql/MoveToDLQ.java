package com.github.thxmasj.statemachine.database.mssql;

import com.github.thxmasj.statemachine.OutboxElement;
import com.github.thxmasj.statemachine.database.Client;
import reactor.core.publisher.Mono;

public class MoveToDLQ {

    private final Client databaseClient;
    private final String sql;

    public MoveToDLQ(Client databaseClient, String schemaName) {
      this.databaseClient = databaseClient;
      // Note: DELETE from backoff and INSERT on dlq needs to be atomic, as the QueueElement.reattempt flag relies on it.
      this.sql =
          """
          DECLARE @entityId UNIQUEIDENTIFIER;
          SET @entityId=:entityId;
          SET XACT_ABORT ON;
          BEGIN TRANSACTION;
            DELETE [{schema}].[OutboxQueueProcessing]
            FROM [{schema}].[OutboxQueueProcessing]
              WITH (INDEX([pkOutboxQueueProcessing]))
            WHERE EntityId=@entityId AND RequestId=:requestId;
            INSERT INTO [{schema}].[OutboxDeadLetterQueue] (
              EntityId,
              EventNumber,
              SubscriberId,
              Cause,
              RequestId
            ) VALUES (
              @entityId,
              :eventNumber,
              :subscriberId,
              :cause,
              :requestId
            );
          COMMIT TRANSACTION;
          """.replace("{schema}", schemaName);
    }

    public Mono<Void> execute(OutboxElement outboxElement, String cause) {
      return databaseClient.sql(sql).name("MoveToDLQ")
          .bind("entityId", outboxElement.entityId().value())
          .bind("eventNumber", outboxElement.eventNumber())
          .bind("subscriberId", outboxElement.subscriber().id())
          .bind("cause", cause)
          .bind("requestId", outboxElement.requestId())
          .update()
          .then();
    }

}
