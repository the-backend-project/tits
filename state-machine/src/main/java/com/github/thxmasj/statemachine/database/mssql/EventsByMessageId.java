package com.github.thxmasj.statemachine.database.mssql;

import static com.github.thxmasj.statemachine.database.jdbc.PreparedStatementSupport.prepare;
import static java.util.Objects.requireNonNull;
import static java.util.Optional.ofNullable;
import static java.util.stream.Collectors.joining;

import com.github.thxmasj.statemachine.EntityId;
import com.github.thxmasj.statemachine.EntityModel;
import com.github.thxmasj.statemachine.Event;
import com.github.thxmasj.statemachine.EventLog;
import com.github.thxmasj.statemachine.SecondaryId;
import com.github.thxmasj.statemachine.database.Row;
import com.github.thxmasj.statemachine.database.UnknownEntity;
import com.github.thxmasj.statemachine.database.jdbc.JDBCRow;
import com.github.thxmasj.statemachine.database.mssql.SchemaNames.Column;
import com.microsoft.sqlserver.jdbc.SQLServerException;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import javax.sql.DataSource;
import reactor.core.publisher.Mono;

@SuppressWarnings("StringConcatenationInLoop")
public class EventsByMessageId {

  private final DataSource dataSource;
  private final Map<EntityModel, String> sqls;
  private final Function<Row, Event<?>> eventMapper;

  public EventsByMessageId(
      DataSource dataSource,
      List<EntityModel> entityModels,
      String schemaName,
      Function<Row, Event<?>> eventMapper
  ) {
    this.dataSource = dataSource;
    this.eventMapper = eventMapper;
    this.sqls = new HashMap<>();
    for (var entityModel : entityModels) {
      var names = new SchemaNames(schemaName, entityModel);
      String sql =
          """
          DECLARE @entityId UNIQUEIDENTIFIER;

          SELECT @entityId=EntityId
          FROM [{schema}].InboxRequest WITH (INDEX(ixMessageId_ClientId))
          WHERE MessageId=:messageId AND ClientId=:clientId;
          
          IF @@ROWCOUNT < 1
            THROW 50000, 'Unknown entity', 1

          SELECT @entityId;

          SELECT EventNumber, Type, Timestamp, MessageId, ClientId, Data
          FROM [{schema}].Event WITH (INDEX(pkEvent))
          WHERE EntityId=@entityId
          ORDER BY EventNumber;
          """.replace("{schema}", schemaName);
      for (var idModel : entityModel.secondaryIds()) {
        sql = sql +
            """
            SELECT {IdColumns}
            FROM {IdTable} WITH (INDEX({IdTablePK}))
            WHERE EntityId=:entityId
            """
                .replace("{IdColumns}", idModel.columns().stream().map(Column::name).collect(joining(",")))
                .replace("{IdTable}", names.qualifiedNames().idTable(idModel))
                .replace("{IdTablePK}", names.idTablePrimaryKeyName(idModel));
      }
      this.sqls.put(entityModel, sql);
    }
  }

  public Mono<EventLog> execute(EntityModel entityModel, String messageId, String clientId) {
    String sqlToPrepare = requireNonNull(sqls.get(entityModel));
    return Mono.fromCallable(() -> {
      try (
          var connection = dataSource.getConnection();
          var statement = prepare(sqlToPrepare, Map.of("messageId", messageId, "clientId", clientId), connection)
      ) {
        statement.execute();
        ResultSet rs = statement.getResultSet();
        if (!rs.next()) {
          throw new IllegalStateException("No result set with entity id. Shouldn't happen.");
        }
        EntityId entityId = ofNullable(rs.getObject(1, UUID.class)).map(EntityId.UUID::new).orElse(null);
        if (entityId == null)
          return null; // Yields Mono.empty
        statement.getMoreResults();
        rs = statement.getResultSet();
        List<Event<?>> events = new ArrayList<>();
        while (rs.next()) {
          events.add(eventMapper.apply(new JDBCRow(rs)));
        }
        List<SecondaryId> secondaryIds = new ArrayList<>();
        for (var idModel : entityModel.secondaryIds()) {
          statement.getMoreResults();
          rs = statement.getResultSet();
          if (rs.next()) // Ids might be added with later events
            secondaryIds.add(idModel.map(rs));
        }
        rs.close();
        if (events.isEmpty() && secondaryIds.isEmpty())
          throw new UnknownEntity(entityModel, entityId, sqlToPrepare);
        return new EventLog(entityModel, entityId, secondaryIds, Collections.unmodifiableList(events));
      }
    })
        .onErrorMap(
            e -> e instanceof SQLServerException f && f.getErrorCode() == 50000,
            _ -> new UnknownEntity(messageId)
        );
  }

}
