package com.github.thxmasj.statemachine.database.mssql;

import com.github.thxmasj.statemachine.Deadline;
import com.github.thxmasj.statemachine.DelaySpecification;
import com.github.thxmasj.statemachine.EntityId;
import com.github.thxmasj.statemachine.EntityModel;
import com.github.thxmasj.statemachine.database.Client;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import reactor.core.publisher.Mono;

public class NextDeadline {

  private final Client databaseClient;
  private final Clock clock;
  private final String sql;
  private final Map<UUID, EntityModel> entityModels;

  public NextDeadline(Client databaseClient, Clock clock, List<EntityModel> entityModels, String schemaName) {
    this.databaseClient = databaseClient;
    this.clock = clock;
    this.entityModels = entityModels.stream().collect(Collectors.toMap(EntityModel::id, Function.identity()));
    this.sql =
      """
      DECLARE @processing TABLE (
        EntityId UNIQUEIDENTIFIER,
        EntityModelId UNIQUEIDENTIFIER,
        EventNumber SMALLINT,
        Deadline DATETIME2(2),
        Attempt INT,
        NextAttemptAt DATETIME2(2),
        CorrelationId VARCHAR(36)
      )
    
      INSERT INTO @processing
      SELECT TOP (1)
        EntityId,
        EntityModelId,
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
      FROM [{schema}].[Timeout] WITH (INDEX([ixDeadline]))
      WHERE Deadline < :now
      ORDER BY Deadline ASC
    
      UPDATE [{schema}].[Timeout] SET
        Deadline=(SELECT NextAttemptAt FROM @processing),
        Attempt=Attempt+1
      FROM [{schema}].[Timeout] WITH (INDEX([ixEntityId]))
      WHERE
        EntityId=(SELECT EntityId FROM @processing) AND
        EventNumber=(SELECT EventNumber FROM @processing) AND
        Deadline=(SELECT Deadline FROM @processing)
    
      IF @@ROWCOUNT = 1
        SELECT EntityId, EntityModelId, EventNumber, NextAttemptAt, CorrelationId
        FROM @processing
      ELSE
        SELECT TOP 0 EntityId, EntityModelId, EventNumber, NextAttemptAt, CorrelationId
        FROM @processing
      """.replace("{schema}", schemaName);
  }

  public Mono<Deadline> execute(DelaySpecification backoff) {
    return databaseClient.sql(sql)
        .bind("now", ZonedDateTime.now(clock).toLocalDateTime())
        .bind("maximumBackoff", backoff.maximum().toSeconds())
        .bind("minimumBackoff", backoff.minimum().toSeconds())
        .bind("backoffPowerBase", backoff.powerBase())
        .map(row -> new Deadline(
            new EntityId.UUID(row.get("EntityId", UUID.class)),
            entityModels.get(row.get("EntityModelId", UUID.class)),
            row.get("EventNumber", Integer.class),
            ZonedDateTime.of(row.get("NextAttemptAt", LocalDateTime.class), clock.getZone()),
            row.get("CorrelationId", String.class)
        ))
        .one();
  }

}
