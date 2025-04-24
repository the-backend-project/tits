package com.github.thxmasj.statemachine.database.mssql;

import com.github.thxmasj.statemachine.EntityModel;
import com.github.thxmasj.statemachine.OutboxElement;
import com.github.thxmasj.statemachine.Subscriber;
import com.github.thxmasj.statemachine.database.Client;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import reactor.core.publisher.Mono;

public class MoveToDLQ {

    private final Client databaseClient;
    private final Map<EntityModel, Map<Subscriber, String>> sqls;

    public MoveToDLQ(Client databaseClient, List<EntityModel> entityModels, String schemaName) {
        this.databaseClient = databaseClient;
        this.sqls = new HashMap<>();
        for (var entityModel : entityModels) {
          sqls.put(entityModel, new HashMap<>());
          var names = new SchemaNames(schemaName, entityModel);
          var q = names.qualifiedNames();
          for (var subscriber : entityModel.subscribers()) {
            // Note: DELETE from backoff and INSERT on dlq needs to be atomic, as the QueueElement.reattempt flag relies on it.
            var sql =
                """
                DECLARE @entityId UNIQUEIDENTIFIER;
                SET @entityId=:entityId;
                SET XACT_ABORT ON;
                BEGIN TRANSACTION;
                  DELETE {processingTable} FROM {processingTable}
                    WITH (INDEX([{processingTablePK}])) WHERE EntityId=@entityId;
                  INSERT INTO {dlqTable}(EntityId, EventNumber, Cause) VALUES (@entityId, :eventNumber, :cause);
                COMMIT TRANSACTION;
                """.replace("{processingTable}", q.processingTable(subscriber))
                    .replace("{processingTablePK}", names.notificationQueueProcessingTablePrimaryKeyName(subscriber))
                    .replace("{dlqTable}", q.dlqTable(subscriber));
            sqls.get(entityModel).put(subscriber, sql);
          }
        }
    }

    public Mono<Void> execute(OutboxElement outboxElement, String cause) {
      String sql = sqls.get(outboxElement.entityModel()).get(outboxElement.subscriber());
      return databaseClient.sql(sql).name("MoveToDLQ")
          .bind("entityId", outboxElement.entityId().value())
          .bind("eventNumber", outboxElement.eventNumber())
          .bind("cause", cause)
          .update()
          .then();
    }

}
