package com.github.thxmasj.statemachine.database.mssql;

import com.github.thxmasj.statemachine.OutboxElement;
import com.github.thxmasj.statemachine.database.Client;
import java.time.Clock;
import java.time.ZonedDateTime;
import reactor.core.publisher.Mono;

public class DequeueAndStoreReceipt {

  private final Client databaseClient;
  private final String sql;
  private final Clock clock;

  public DequeueAndStoreReceipt(Client databaseClient, String schemaName, Clock clock) {
    this.databaseClient = databaseClient;
    this.clock = clock;
    this.sql =
        """
        SET XACT_ABORT ON;
        BEGIN TRANSACTION;

        DECLARE @entityId UNIQUEIDENTIFIER;
        SET @entityId=:entityId;

        DELETE [{schema}].[OutboxQueueProcessing] FROM [{schema}].[OutboxQueueProcessing] WITH (INDEX([pkOutboxQueueProcessing]))
        WHERE EntityId=@entityId AND RequestId=:requestId;

        DELETE [{schema}].[OutboxQueue] FROM [{schema}].[OutboxQueue] WITH (INDEX([pkOutboxQueue]))
        WHERE ElementId=:elementId AND RequestId=:requestId;

        INSERT INTO [{schema}].[OutboxResponse] (
          EntityId,
          EventNumber,
          Timestamp,
          Data,
          RequestId
        ) VALUES (
          @entityId,
          :eventNumber,
          :timestamp,
          :data,
          :requestId
        );
        COMMIT TRANSACTION;
        """.replace("{schema}", schemaName);
  }

  public Mono<Void> execute(
      OutboxElement outboxElement,
      String responseMessage,
      ZonedDateTime timestamp
  ) {
    Client.Query.Builder builder = databaseClient.sql(sql).name("DequeueAndStoreReceipt");
    return builder
        .bind("elementId", outboxElement.queueElementId())
        .bind("entityId", outboxElement.entityId().value())
        .bind("eventNumber", outboxElement.eventNumber())
        .bind("data", responseMessage)
        .bind("timestamp", timestamp.withZoneSameInstant(clock.getZone()).toLocalDateTime())
        .bind("requestId", outboxElement.requestId())
        .update()
        .then();
  }

}
