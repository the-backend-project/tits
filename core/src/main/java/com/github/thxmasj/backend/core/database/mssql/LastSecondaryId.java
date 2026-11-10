package com.github.thxmasj.backend.core.database.mssql;

import static com.github.thxmasj.backend.core.database.jdbc.PreparedStatementSupport.prepare;

import com.github.thxmasj.backend.core.SecondaryId;
import com.github.thxmasj.backend.core.SecondaryIdModel;
import com.github.thxmasj.backend.core.SecondaryIdModel.Group;
import com.github.thxmasj.backend.core.database.Parameter;
import java.sql.ResultSet;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import reactor.core.publisher.Mono;

/// The last id of a group, from the id event of the last member of the group
public class LastSecondaryId {

  private final DataSource dataSource;
  private final String sql;

  public LastSecondaryId(DataSource dataSource, String schemaName) {
    this.dataSource = dataSource;
    this.sql =
        """
        SELECT TOP(1) id.Data
        FROM [{schema}].[IndexEvent] ie WITH (INDEX(ixData))
          INNER JOIN [{schema}].[IdEvent] id WITH (INDEX(pkIdEvent)) ON id.EntityId=ie.EntityId AND id.Type=ie.Type
        WHERE ie.Data=:group AND ie.Type=:type
        ORDER BY ie.Timestamp DESC, ie.EventNumber DESC, id.EventNumber DESC
        """.replace("{schema}", schemaName);
  }

  public <T> Mono<SecondaryId<T>> execute(SecondaryIdModel<T> idModel, Object entityGroup) {
    return Mono.fromCallable(() -> {
      try (
          var connection = dataSource.getConnection();
          var statement = prepare(
              sql,
              Map.of(
                  "group", new Parameter<>(byte[].class, Group.key(entityGroup)),
                  "type", new Parameter<>(UUID.class, idModel.id())
              ),
              connection
          )
      ) {
        statement.execute();
        ResultSet rs = statement.getResultSet();
        if (!rs.next()) {
          return null; // The resulting Mono completes empty
        }
        return new SecondaryId<>(idModel, idModel.dataType().unmarshal(rs.getBytes(1)));
      }
    });
  }

}
