package com.github.thxmasj.statemachine.database.mssql;

import static com.github.thxmasj.statemachine.database.jdbc.PreparedStatementSupport.prepare;
import static java.util.Objects.requireNonNull;

import com.github.thxmasj.statemachine.EntityId;
import com.github.thxmasj.statemachine.EntityModel;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import com.github.thxmasj.statemachine.Subscriber;
import reactor.core.publisher.Mono;

public class OutgoingRequestByEvent {

  private final DataSource dataSource;
  private final Map<EntityModel, Map<Subscriber, String>> sqls;

  public OutgoingRequestByEvent(DataSource dataSource, List<EntityModel> entityModels, String schemaName) {
    this.dataSource = dataSource;
    this.sqls = new HashMap<>();
    for (var entityModel : entityModels) {
      var names = new SchemaNames(schemaName, entityModel);
      this.sqls.put(entityModel, new HashMap<>());
      for (var subscriber : entityModel.subscribers()) {
        String sql =
            """
            SELECT Data
            FROM {OutboxTable} WITH (INDEX({OutboxTablePK}))
            WHERE EntityId=:entityId AND EventNumber=:eventNumber
            """
                .replace("{OutboxTable}", names.qualifiedNames().outboxRequestTable(subscriber))
                .replace("{OutboxTablePK}", names.outboxRequestTablePrimaryKeyName(subscriber));
        this.sqls.get(entityModel).put(subscriber, sql);
      }
    }
  }

  public Mono<String> execute(EntityModel entityModel, Subscriber subscriber, EntityId entityId, int eventNumber) {
    String sqlToPrepare = requireNonNull(sqls.get(entityModel).get(subscriber));
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
        return rs.getString("Data");
      }
    });
  }

}
