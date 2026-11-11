package com.github.thxmasj.backend.core.database.postgres;

import static com.github.thxmasj.backend.core.database.postgres.Names.table;

import com.github.thxmasj.backend.core.EntityModel;
import java.time.Clock;
import java.util.List;
import javax.sql.DataSource;

public class EventsByEntityId extends com.github.thxmasj.backend.core.database.mssql.EventsByEntityId {

  public EventsByEntityId(
      DataSource dataSource,
      List<EntityModel> entityModels,
      String schemaName,
      Clock clock
  ) {
    super(
        dataSource,
        entityModels,
        eventTable -> """
            SELECT EventNumber, Type, Timestamp, Data
            FROM {table}
            WHERE EntityId=:entityId
            ORDER BY EventNumber;
            """.replace("{table}", table(schemaName, eventTable)),
        clock
    );
  }

}
