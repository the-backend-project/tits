package com.github.thxmasj.statemachine.database.mssql;

import static com.github.thxmasj.statemachine.database.jdbc.PreparedStatementSupport.prepare;

import java.sql.ResultSet;
import java.util.Map;
import javax.sql.DataSource;
import com.github.thxmasj.statemachine.message.http.HttpMessageParser;
import com.github.thxmasj.statemachine.message.http.HttpResponseMessage;
import reactor.core.publisher.Mono;

public class OutgoingResponseAndRequestDigestByRequest {

  public record InboxEntry(
      byte[] requestDigest,
      HttpResponseMessage responseMessage
  ) {}

  private final DataSource dataSource;
  private final String sql;

  public OutgoingResponseAndRequestDigestByRequest(DataSource dataSource, String schemaName) {
    this.dataSource = dataSource;
    this.sql =
        """
        SELECT rq.Digest, rs.Data
        FROM [{schema}].[InboxRequest] rq WITH (INDEX(ixMessageId_ClientId))
        LEFT OUTER JOIN [{schema}].[InboxResponse] rs WITH (INDEX(pkInboxResponse))
        ON rs.EntityId=rq.EntityId AND rs.RequestId=rq.Id
        WHERE rq.MessageId=:messageId AND rq.ClientId=:clientId
        """.replace("{schema}", schemaName);
  }

  public Mono<InboxEntry> execute(String messageId, String clientId) {
    return Mono.fromCallable(() -> {
          try (
              var connection = dataSource.getConnection();
              var statement = prepare(sql, Map.of("messageId", messageId, "clientId", clientId), connection)
          ) {
            statement.execute();
            ResultSet rs = statement.getResultSet();
            if (!rs.next()) {
              return null; // Yields Mono.empty
            }
            return new InboxEntry(rs.getBytes("Digest"), HttpMessageParser.parseResponse(rs.getString("Data")));
          }
        });
  }

}
