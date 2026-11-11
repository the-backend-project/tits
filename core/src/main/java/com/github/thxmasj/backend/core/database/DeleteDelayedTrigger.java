package com.github.thxmasj.backend.core.database;

import reactor.core.publisher.Mono;
import java.util.UUID;

public class DeleteDelayedTrigger {

  private final Client databaseClient;
  private final String sql;

  /// @param sql the SQL deleting the delayed trigger of an entity with an event number
  public DeleteDelayedTrigger(Client databaseClient, String sql) {
    this.databaseClient = databaseClient;
    this.sql = sql;
  }

  public Mono<Integer> execute(UUID entityId, int eventNumber) {
    return databaseClient.sql(sql)
        .name("DeleteDelayedTrigger")
        .bind("entityId", entityId)
        .bind("eventNumber", eventNumber)
        .update();
  }
}
