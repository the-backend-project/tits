package com.github.thxmasj.statemachine.database.mssql;

import com.github.thxmasj.statemachine.EntityModel;
import com.github.thxmasj.statemachine.OutboxElement;
import com.github.thxmasj.statemachine.Subscriber;
import com.github.thxmasj.statemachine.database.Client;
import java.time.Clock;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import reactor.core.publisher.Mono;

public class DequeueAndStoreReceipt {

  private final Client databaseClient;
  private final Map<EntityModel, Map<Subscriber, String>> sqls;
  private final Clock clock;

  public DequeueAndStoreReceipt(Client databaseClient, List<EntityModel> entityModels, String schemaName, Clock clock) {
    this.databaseClient = databaseClient;
    this.sqls = new HashMap<>();
    this.clock = clock;
    for (var entityModel : entityModels) {
      sqls.put(entityModel, new HashMap<>());
      var names = new SchemaNames(schemaName, entityModel);
      var q = names.qualifiedNames();
      for (var subscriber : entityModel.subscribers().stream().toList()) {
        String sql =
            """
            SET XACT_ABORT ON;
            BEGIN TRANSACTION;

            DECLARE @entityId UNIQUEIDENTIFIER;
            SET @entityId=:entityId;

            DELETE {processingTable} FROM {processingTable} WITH (INDEX([{processingTablePK}]))
            WHERE EntityId=@entityId

            DELETE {queueTable} FROM {queueTable} WITH (INDEX([{queueTablePK}]))
            WHERE ElementId=:elementId;

            INSERT INTO {inboxTable} (
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
            """
                .replace("{processingTable}", q.processingTable(subscriber))
                .replace("{processingTablePK}", names.notificationQueueProcessingTablePrimaryKeyName(subscriber))
                .replace("{queueTable}", q.queueTable(subscriber))
                .replace("{queueTablePK}", names.queueTablePrimaryKeyName(subscriber))
                .replace("{inboxTable}", q.outboxResponseTable(subscriber));
        sqls.get(entityModel).put(subscriber, sql);
      }
    }

  }

  public Mono<Void> execute(
      OutboxElement outboxElement,
      String responseMessage,
      ZonedDateTime timestamp
  ) {
    String sql = sqls.get(outboxElement.entityModel()).get(outboxElement.subscriber());
    Client.Query.Builder builder = databaseClient.sql(sql).name("DequeueAndStoreReceipt-" + outboxElement.subscriber());
    return builder
        .bind("elementId", outboxElement.queueElementId())
        .bind("entityId", outboxElement.entityId().value())
        .bind("eventNumber", outboxElement.eventNumber())
        .bind("data", responseMessage)
        .bind("timestamp", timestamp.withZoneSameInstant(clock.getZone()).toLocalDateTime())
        .bind("requestId", outboxElement.outboxElementId())
        .update()
        .then();
  }

}
