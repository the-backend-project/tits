package com.github.thxmasj.backend.core.internal.database.sql;

import com.github.thxmasj.backend.core.database.DatabaseClient;
import com.github.thxmasj.backend.core.internal.database.DeleteDelayedTrigger;
import java.util.UUID;
import reactor.core.publisher.Mono;

public class SqlDeleteDelayedTrigger implements DeleteDelayedTrigger {

  private final DatabaseClient databaseClient;
  private final String sql;

  /// @param sql the SQL deleting the delayed trigger of an entity with an event number
  public SqlDeleteDelayedTrigger(DatabaseClient databaseClient, String sql) {
    this.databaseClient = databaseClient;
    this.sql = sql;
  }

  @Override
  public Mono<Integer> execute(UUID entityId, int eventNumber) {
    return databaseClient.sql(sql)
        .name("DeleteDelayedTrigger")
        .bind("entityId", entityId)
        .bind("eventNumber", eventNumber)
        .update();
  }
}
