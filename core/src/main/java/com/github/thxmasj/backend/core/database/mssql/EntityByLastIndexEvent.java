package com.github.thxmasj.backend.core.database.mssql;

import static com.github.thxmasj.backend.core.database.jdbc.PreparedStatementSupport.prepare;

import com.github.thxmasj.backend.core.EntityId;
import com.github.thxmasj.backend.core.database.Parameter;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import reactor.core.publisher.Mono;

/// The entity last indexed by a key, i.e. the index entity with the last index event of a type with the key as data, or
/// the one indexed before it, and so on
public class EntityByLastIndexEvent {

  private final DataSource dataSource;
  private final String sql;

  public EntityByLastIndexEvent(DataSource dataSource, String schemaName) {
    this.dataSource = dataSource;
    this.sql =
        """
        SELECT TOP(1) EntityId
        FROM (
          SELECT TOP(:position) EntityId, Timestamp, EventNumber
          FROM [{schema}].[IndexEvent] WITH (INDEX(ixData))
          WHERE Data=:key AND Type=:type
          ORDER BY Timestamp DESC, EventNumber DESC
        ) Indexed
        ORDER BY Timestamp ASC, EventNumber ASC
        """.replace("{schema}", schemaName);
  }

  /// Completes empty if no entity is indexed by the key. If fewer entities than the position are indexed by it, the
  /// first one is given.
  public Mono<EntityId> execute(UUID type, byte[] key, int position) {
    return Mono.fromCallable(() -> {
      try (
          var connection = dataSource.getConnection();
          var statement = prepare(
              sql,
              Map.of(
                  "type", new Parameter<>(UUID.class, type),
                  "key", new Parameter<>(byte[].class, key),
                  "position", new Parameter<>(Integer.class, position)
              ),
              connection
          );
          var rs = statement.executeQuery()
      ) {
        return rs.next() ? new EntityId.UUID(rs.getObject("EntityId", UUID.class)) : null;
      }
    });
  }

}
