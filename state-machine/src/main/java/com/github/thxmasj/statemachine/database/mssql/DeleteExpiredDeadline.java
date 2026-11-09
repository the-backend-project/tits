package com.github.thxmasj.statemachine.database.mssql;

import com.github.thxmasj.statemachine.EntityId;
import com.github.thxmasj.statemachine.database.Client;
import java.util.UUID;
import reactor.core.publisher.Mono;

public class DeleteExpiredDeadline {

  private final Client databaseClient;
  private final String sql;

  public DeleteExpiredDeadline(
      Client databaseClient,
      String schema
  ) {
    this.databaseClient = databaseClient;
    this.sql = "DELETE FROM [{schema}].[Timeout] WHERE EntityId = :entityId AND EventNumber = :eventNumber"
        .replace("{schema}", schema);
  }

  public Mono<Integer> execute(UUID entityId, int eventNumber) {
    return databaseClient.sql(sql)
        .name("DeleteExpiredDeadline")
        .bind("entityId", entityId)
        .bind("eventNumber", eventNumber)
        .update();
  }
}
