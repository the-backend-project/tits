package com.github.thxmasj.backend.core.internal.database;

import com.github.thxmasj.backend.core.database.DatabaseClient;
import java.util.UUID;
import reactor.core.publisher.Mono;

public class DeleteDelayedTrigger {

  private final DatabaseClient databaseClient;
  private final String sql;

  /// @param sql the SQL deleting the delayed trigger of an entity with an event number
  public DeleteDelayedTrigger(DatabaseClient databaseClient, String sql) {
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
