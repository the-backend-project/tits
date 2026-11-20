package com.github.thxmasj.backend.database.mssql.internal;

import com.github.thxmasj.backend.core.EntityModel;
import com.github.thxmasj.backend.core.EventType;
import com.github.thxmasj.backend.core.database.DatabaseClient;
import com.github.thxmasj.backend.core.internal.ResolvedModels;
import com.github.thxmasj.backend.core.internal.database.Storage;
import com.github.thxmasj.backend.core.internal.database.sql.SqlConditionalTriggers;
import com.github.thxmasj.backend.core.internal.database.sql.SqlDeleteDelayedTrigger;
import com.github.thxmasj.backend.core.internal.database.sql.SqlEntitiesByIndex;
import com.github.thxmasj.backend.core.internal.database.sql.SqlEventsByEntityId;
import com.github.thxmasj.backend.core.internal.database.sql.SqlEventsByIdEvent;
import com.github.thxmasj.backend.core.internal.database.sql.SqlEventsByLastIndexEvent;
import com.github.thxmasj.backend.core.internal.database.sql.SqlNextDelayedTrigger;
import com.github.thxmasj.backend.core.internal.database.sql.SqlStorageProvider;
import java.time.Clock;
import java.util.List;
import java.util.Map;

/// The storage of SQL Server
public class MssqlStorage extends SqlStorageProvider {

  @Override
  public String database() {
    return "mssql";
  }

  @Override
  protected Storage storage(
      DatabaseClient client,
      ResolvedModels models,
      String schemaName,
      Clock clock
  ) {
    List<EntityModel> entityModels = models.entityModels();
    Map<EntityModel, List<EventType<?, ?>>> eventTypesByEntityModel = models.eventTypesByEntityModel();
    return new Storage(
        new ChangeState(client, schemaName, clock),
        new SqlEventsByEntityId(client, table -> Queries.eventsByEntityId(schemaName, table), entityModels, clock),
        new SqlEventsByIdEvent(client, Queries.eventsByIdEvent(schemaName), eventTypesByEntityModel, clock),
        new SqlEventsByLastIndexEvent(client, Queries.eventsByLastIndexEvent(schemaName), clock),
        new SqlEntitiesByIndex(client, Queries.entitiesByIndex(schemaName), eventTypesByEntityModel, clock),
        new SqlNextDelayedTrigger(client, Queries.nextDelayedTrigger(schemaName), models.eventTypes(), clock, entityModels),
        new SqlDeleteDelayedTrigger(client, Queries.deleteDelayedTrigger(schemaName)),
        new SqlConditionalTriggers(client, Queries.conditionalTriggers(schemaName), eventTypesByEntityModel, clock)
    );
  }
}
