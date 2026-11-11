package com.github.thxmasj.backend.core.database.mssql;

import com.github.thxmasj.backend.core.database.Client;
import reactor.core.publisher.Mono;
import java.util.UUID;

public class DeleteDelayedTrigger {

  private final Client databaseClient;
  private final String sql;

  public DeleteDelayedTrigger(
      Client databaseClient,
      String schema
  ) {
    this.databaseClient = databaseClient;
    // Quoted identifiers work in both SQL Server and PostgreSQL
    this.sql = "DELETE FROM \"{schema}\".\"DelayedTrigger\" WHERE EntityId = :entityId AND EventNumber = :eventNumber"
        .replace("{schema}", schema);
  }

  public Mono<Integer> execute(UUID entityId, int eventNumber) {
    return databaseClient.sql(sql)
        .name("DeleteDelayedTrigger")
        .bind("entityId", entityId)
        .bind("eventNumber", eventNumber)
        .update();
  }
}
