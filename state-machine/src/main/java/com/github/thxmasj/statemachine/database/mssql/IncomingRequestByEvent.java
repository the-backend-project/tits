package com.github.thxmasj.statemachine.database.mssql;

import com.github.thxmasj.statemachine.EntityId;
import com.github.thxmasj.statemachine.EntityModel;
import com.github.thxmasj.statemachine.message.http.HttpMessageParser;
import com.github.thxmasj.statemachine.message.http.HttpRequestMessage;
import reactor.core.publisher.Mono;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.github.thxmasj.statemachine.database.jdbc.PreparedStatementSupport.prepare;
import static java.util.Objects.requireNonNull;

public class IncomingRequestByEvent {

  private final DataSource dataSource;
  private final Map<EntityModel, String> sqls;

  public IncomingRequestByEvent(DataSource dataSource, List<EntityModel> entityModels, String schemaName) {
    this.dataSource = dataSource;
    this.sqls = new HashMap<>();
    for (var entityModel : entityModels) {
      String sql =
          """
          SELECT Data
          FROM [{schema}].[InboxRequest] WITH (INDEX(pkInboxRequest))
          WHERE EntityId=:entityId AND EventNumber=:eventNumber
          """.replace("{schema}", schemaName);
      this.sqls.put(entityModel, sql);
    }
  }

  public Mono<HttpRequestMessage> execute(EntityModel entityModel, EntityId entityId, int eventNumber) {
    String sqlToPrepare = requireNonNull(sqls.get(entityModel));
    return Mono.fromCallable(() -> {
      try (
          var connection = dataSource.getConnection();
          var statement = prepare(sqlToPrepare, Map.of("entityId", entityId.value(), "eventNumber", eventNumber), connection)
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
