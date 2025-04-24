package com.github.thxmasj.statemachine.database.mssql;

import com.github.thxmasj.statemachine.Deadline;
import com.github.thxmasj.statemachine.DelaySpecification;
import com.github.thxmasj.statemachine.EntityId;
import com.github.thxmasj.statemachine.EntityModel;
import com.github.thxmasj.statemachine.database.Client;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import reactor.core.publisher.Mono;

public class NextDeadline {

  private final Client databaseClient;
  private final Clock clock;
  private final String schemaName;
  private final Map<EntityModel, String> sqls;

  public NextDeadline(Client databaseClient, Clock clock, List<EntityModel> entityModels, String schemaName) {
    this.databaseClient = databaseClient;
    this.clock = clock;
    this.schemaName = schemaName;
    this.sqls = new HashMap<>();
    for (EntityModel entityModel : entityModels) {
      var names = new SchemaNames(schemaName, entityModel);
      String sql =
          """
          DECLARE @processing TABLE (
            EntityId UNIQUEIDENTIFIER,
            EventNumber SMALLINT,
            Deadline DATETIME2(2),
            Attempt INT,
            NextAttemptAt DATETIME2(2),
            CorrelationId VARCHAR(36)
          )
        
          INSERT INTO @processing
          SELECT TOP (1)
            EntityId,
            EventNumber,
            Deadline,
            Attempt,
            DATEADD(
                    millisecond,
                    1000 * (SELECT MIN(i) FROM (VALUES
                      (:maximumBackoff),
                      (:minimumBackoff*POWER(:backoffPowerBase, (SELECT MIN(j) FROM (VALUES (Attempt), (50)) as N(j))))
                    ) AS T(i)),
                    :now
                  ),
            CorrelationId
          FROM {timeoutTable} WITH (INDEX([ixDeadline]))
          WHERE Deadline < :now
          ORDER BY Deadline ASC
        
          UPDATE {timeoutTable} SET
            Deadline=(SELECT NextAttemptAt FROM @processing),
            Attempt=Attempt+1
          FROM {timeoutTable} WITH (INDEX([ixEntityId]))
          WHERE
            EntityId=(SELECT EntityId FROM @processing) AND
            EventNumber=(SELECT EventNumber FROM @processing) AND
            Deadline=(SELECT Deadline FROM @processing)
        
          IF @@ROWCOUNT = 1
            SELECT EntityId, EventNumber, NextAttemptAt, CorrelationId
            FROM @processing
          ELSE
            SELECT TOP 0 EntityId, EventNumber, NextAttemptAt, CorrelationId
            FROM @processing
          """.replace("{timeoutTable}", names.qualifiedNames().timeoutTable());
      sqls.put(entityModel, sql);
    }
  }

  public Mono<Deadline> execute(EntityModel entityModel, DelaySpecification backoff) {
    String sqlToPrepare = sqls.get(entityModel);
    var names = new SchemaNames(schemaName, entityModel);
    return databaseClient.sql(sqlToPrepare)
        .bind("now", ZonedDateTime.now(clock).toLocalDateTime())
        .bind("maximumBackoff", backoff.maximum().toSeconds())
        .bind("minimumBackoff", backoff.minimum().toSeconds())
        .bind("backoffPowerBase", backoff.powerBase())
        .map(row -> new Deadline(
            new EntityId.UUID(row.get("EntityId", java.util.UUID.class)),
            row.get("EventNumber", Integer.class),
            ZonedDateTime.of(row.get("NextAttemptAt", LocalDateTime.class), clock.getZone()),
            row.get("CorrelationId", String.class)
        ))
        .one();
  }

}
