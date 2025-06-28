package com.github.thxmasj.statemachine.database.mssql;

import static com.github.thxmasj.statemachine.database.jdbc.PreparedStatementSupport.prepare;

import com.github.thxmasj.statemachine.EntityId;
import java.sql.ResultSet;
import java.util.Map;
import javax.sql.DataSource;
import com.github.thxmasj.statemachine.message.http.HttpMessageParser;
import com.github.thxmasj.statemachine.message.http.HttpRequestMessage;
import reactor.core.publisher.Mono;

public class OutgoingRequestByEvent {

  private final DataSource dataSource;
  private final String sql;

  public OutgoingRequestByEvent(DataSource dataSource, String schemaName) {
    this.dataSource = dataSource;
    this.sql =
        """
        SELECT Data
        FROM [{schema}].[OutboxRequest] WITH (INDEX(pkOutboxRequest))
        WHERE EntityId=:entityId AND EventNumber=:eventNumber
        """.replace("{schema}", schemaName);
  }

  public Mono<HttpRequestMessage> execute(EntityId entityId, int eventNumber) {
    return Mono.fromCallable(() -> {
      try (
          var connection = dataSource.getConnection();
          var statement = prepare(sql, Map.of("entityId", entityId.value(), "eventNumber", eventNumber), connection)
      ) {
        statement.execute();
        ResultSet rs = statement.getResultSet();
        if (!rs.next()) {
          return null; // Yields Mono.empty
        }
        return HttpMessageParser.parseRequest(rs.getString("Data"));
      }
    });
  }

}
