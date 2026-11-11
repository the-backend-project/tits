package com.github.thxmasj.backend.core.database.postgres;

import static com.github.thxmasj.backend.core.database.postgres.Names.table;

import com.github.thxmasj.backend.core.EntityModel;
import com.github.thxmasj.backend.core.EventType;
import com.github.thxmasj.backend.core.database.Client;
import java.time.Clock;
import java.util.List;

public class NextDelayedTrigger extends com.github.thxmasj.backend.core.database.mssql.NextDelayedTrigger {

  public NextDelayedTrigger(
      Client databaseClient,
      List<EventType<?, ?>> eventTypes,
      Clock clock,
      List<EntityModel> entityModels,
      String schemaName
  ) {
    // Postpone the delayed trigger with the earliest passed deadline with an exponential backoff. Concurrent resolvers
    // skip it while it is locked, and do not find it afterwards as its deadline has not passed.
    super(
        databaseClient,
        """
        UPDATE {DelayedTrigger} t SET
          Deadline=n.NextAttemptAt,
          Attempt=t.Attempt+1
        FROM (
          SELECT
            EntityId,
            EventNumber,
            Deadline,
            :now + make_interval(secs => LEAST(
              :maximumBackoff,
              :minimumBackoff*POWER(:backoffPowerBase, LEAST(Attempt, 50))
            )) AS NextAttemptAt
          FROM {DelayedTrigger}
          WHERE Deadline < :now
          ORDER BY Deadline ASC
          LIMIT 1
          FOR UPDATE SKIP LOCKED
        ) n
        WHERE
          t.EntityId=n.EntityId AND
          t.EventNumber=n.EventNumber AND
          t.Deadline=n.Deadline
        RETURNING t.EntityId, t.EntityModelId, t.EventNumber, t.Type, t.Data, t.Deadline AS NextAttemptAt, t.CorrelationId
        """.replace("{DelayedTrigger}", table(schemaName, "DelayedTrigger")),
        eventTypes,
        clock,
        entityModels
    );
  }

}
