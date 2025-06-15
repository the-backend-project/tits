package com.github.thxmasj.statemachine.database.mssql;

import static com.github.thxmasj.statemachine.database.jdbc.PreparedStatementSupport.prepare;
import static java.util.Objects.requireNonNull;

import com.github.thxmasj.statemachine.EntityModel;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import reactor.core.publisher.Mono;

public class OutgoingResponseAndRequestDigestByRequest {

  public record InboxEntry(
      byte[] requestDigest,
      String responseMessage
  ) {}

  private final DataSource dataSource;
  private final Map<EntityModel, String> sqls;

  public OutgoingResponseAndRequestDigestByRequest(DataSource dataSource, List<EntityModel> entityModels, String schemaName) {
    this.dataSource = dataSource;
    this.sqls = new HashMap<>();
    for (var entityModel : entityModels) {
      var names = new SchemaNames(schemaName, entityModel);
      String sql =
          """
          SELECT rq.Digest, rs.Data
          FROM {InboxTable} rq WITH (INDEX(ixMessageId_ClientId))
          LEFT OUTER JOIN {OutboxTable} rs WITH (INDEX({OutboxTablePK}))
          ON rs.EntityId=rq.EntityId AND rs.RequestId=rq.Id
          WHERE rq.MessageId=:messageId AND rq.ClientId=:clientId
          """
              .replace("{InboxTable}", names.qualifiedNames().inboxTable())
              .replace("{OutboxTable}", names.qualifiedNames().outboxTable())
              .replace("{OutboxTablePK}", names.outboxTablePrimaryKeyName());
      this.sqls.put(entityModel, sql);
    }
  }

  public Mono<InboxEntry> execute(EntityModel entityModel, String messageId, String clientId) {
    String sqlToPrepare = requireNonNull(sqls.get(entityModel));
    return Mono.fromCallable(() -> {
          try (
              var connection = dataSource.getConnection();
              var statement = prepare(sqlToPrepare, Map.of("messageId", messageId, "clientId", clientId), connection)
          ) {
            statement.execute();
            ResultSet rs = statement.getResultSet();
            if (!rs.next()) {
              return null; // Yields Mono.empty
            }
            return new InboxEntry(rs.getBytes("Digest"), rs.getString("Data"));
          }
        });
  }

}
