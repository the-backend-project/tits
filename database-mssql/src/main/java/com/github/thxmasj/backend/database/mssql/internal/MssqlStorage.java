package com.github.thxmasj.backend.database.mssql.internal;

import com.github.thxmasj.backend.core.EntityModel;
import com.github.thxmasj.backend.core.EventType;
import com.github.thxmasj.backend.core.database.DatabaseClient;
import com.github.thxmasj.backend.core.internal.EventTypes;
import com.github.thxmasj.backend.core.internal.database.ConditionalTriggers;
import com.github.thxmasj.backend.core.internal.database.DeleteDelayedTrigger;
import com.github.thxmasj.backend.core.internal.database.EntitiesByIndex;
import com.github.thxmasj.backend.core.internal.database.EventsByEntityId;
import com.github.thxmasj.backend.core.internal.database.EventsByIdEvent;
import com.github.thxmasj.backend.core.internal.database.EventsByLastIndexEvent;
import com.github.thxmasj.backend.core.internal.database.NextDelayedTrigger;
import com.github.thxmasj.backend.core.internal.database.Storage;
import com.github.thxmasj.backend.core.internal.database.StorageProvider;
import java.time.Clock;
import java.util.List;
import java.util.Map;

/// The storage of SQL Server
public class MssqlStorage implements StorageProvider {

  @Override
  public String database() {
    return "mssql";
  }

  @Override
  public Storage storage(
      DatabaseClient client,
      List<EntityModel> entityModels,
      String schemaName,
      Clock clock
  ) {
    Map<EntityModel, List<EventType<?, ?>>> eventTypesByEntityModel = EventTypes.eventTypesByEntityModel(entityModels);
    return new Storage(
        new ChangeState(client, schemaName, clock),
        new EventsByEntityId(client, table -> Queries.eventsByEntityId(schemaName, table), entityModels, clock),
        new EventsByIdEvent(client, Queries.eventsByIdEvent(schemaName), eventTypesByEntityModel, clock),
        new EventsByLastIndexEvent(client, Queries.eventsByLastIndexEvent(schemaName), clock),
        new EntitiesByIndex(client, Queries.entitiesByIndex(schemaName), eventTypesByEntityModel, clock),
        new NextDelayedTrigger(client, Queries.nextDelayedTrigger(schemaName), EventTypes.eventTypes(entityModels), clock, entityModels),
        new DeleteDelayedTrigger(client, Queries.deleteDelayedTrigger(schemaName)),
        new ConditionalTriggers(client, Queries.conditionalTriggers(schemaName), eventTypesByEntityModel, clock)
    );
  }
}
