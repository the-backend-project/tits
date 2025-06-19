package com.github.thxmasj.statemachine.database.mssql;

import static java.util.Optional.ofNullable;
import static java.util.stream.Collectors.joining;

import com.github.thxmasj.statemachine.database.EntityGroupNotInitialised;
import com.github.thxmasj.statemachine.EventLog;
import com.github.thxmasj.statemachine.EntityId;
import com.github.thxmasj.statemachine.EntityModel;
import com.github.thxmasj.statemachine.Event;
import com.github.thxmasj.statemachine.SecondaryId;
import com.github.thxmasj.statemachine.database.Client.MssqlException;
import com.github.thxmasj.statemachine.database.mssql.SchemaNames.Column;
import com.github.thxmasj.statemachine.database.mssql.SchemaNames.SecondaryIdModel;
import com.microsoft.sqlserver.jdbc.SQLServerException;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;
import reactor.core.publisher.Mono;

@SuppressWarnings("StringConcatenationInLoop")
public class EventsByLastEntity {

  private final DataSource dataSource;
  private final Clock clock;
  private final Map<SecondaryIdModel, String> sql;

  public EventsByLastEntity(
      DataSource dataSource,
      List<EntityModel> entityModels,
      String schemaName,
      Clock clock
  ) {
    this.dataSource = dataSource;
    this.clock = clock;
    this.sql = new HashMap<>();
    for (var entityModel : entityModels) {
      var names = new SchemaNames(schemaName, entityModel);
      for (var idModel : entityModel.secondaryIds()) {
        if (idModel.group() == null) {
          continue;
        }
        var sql =
            """
            DECLARE @entityId UNIQUEIDENTIFIER;

            SELECT TOP(?) EntityId, #IdTableColumns#
            INTO #TempIdTable
            FROM #IdTable# WITH (INDEX(#IdTableIndex#))
            WHERE #GroupFilter#
            ORDER BY #GroupOrdering#

            IF (SELECT COUNT(*) FROM #TempIdTable) < 1
              THROW 50000, 'Entity group not initialised', 1

            SELECT TOP(1) @entityId=EntityId FROM #TempIdTable ORDER BY #ReverseGroupOrdering#

            SELECT @entityId

            SELECT EventNumber, Type, Timestamp, MessageId, ClientId, Data
            FROM #EventTable# WITH (INDEX(#EventTablePK#))
            WHERE EntityId=@entityId
            ORDER BY EventNumber;
            """
                .replaceAll(
                    "#IdTableColumns#",
                    idModel.columns().stream().map(Column::name).collect(joining(","))
                )
                .replaceAll("#IdTable#", names.qualifiedNames().idTable(idModel))
                .replaceAll("#IdTableIndex#", names.indexName(idModel.columns()))
                .replaceAll(
                    "#GroupFilter#",
                    idModel
                        .group()
                        .groupColumns()
                        .stream()
                        .map(c -> c.name() + "=?")
                        .collect(joining(" AND "))
                )
                .replaceAll("#GroupOrdering#", idModel
                    .group()
                    .groupOrdering()
                    .stream()
                    .map(c -> c.column().name() + " " + (c.descending() ? "DESC" : "ASC"))
                    .collect(joining(", ")))
                .replaceAll("#ReverseGroupOrdering#", idModel
                    .group()
                    .groupOrdering()
                    .stream()
                    .map(c -> c.column().name() + " " + (c.descending() ? "ASC" : "DESC"))
                    .collect(joining(", ")))
                .replaceAll("#EventTable#", names.qualifiedNames().eventTable())
                .replaceAll("#EventTablePK#", names.eventTablePrimaryKeyName());
        for (var idModel2 : entityModel.secondaryIds()) {
          sql = sql +
              """
              SELECT #IdColumns#
              FROM #IdTable# WITH (INDEX(#IdTablePK#))
              WHERE EntityId=@entityId
              """
                  .replaceAll("#IdColumns#", idModel2.columns().stream().map(Column::name).collect(joining(",")))
                  .replaceAll("#IdTable#", names.qualifiedNames().idTable(idModel2))
                  .replaceAll("#IdTablePK#", names.idTablePrimaryKeyName(idModel2));

        }
        this.sql.put(idModel, sql);
      }
    }
  }

  public Mono<EventLog> execute(EntityModel entityModel, SecondaryIdModel idModel, Object entityGroup, int lastPosition) {
    Objects.requireNonNull(entityModel);
    Objects.requireNonNull(entityGroup);
    String sqlToPrepare = sql.get(idModel);
    return Mono.fromCallable(() -> {
          //noinspection SqlSourceToSinkFlow
          try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(sqlToPrepare)) {
        statement.setInt(1, lastPosition);
        int i = 0;
        for (; i < idModel.group().groupColumns().size(); i++) {
          statement.setString(i + 2, idModel.group().groupColumns().get(i).value().apply(entityGroup).toString());
        }
        statement.execute();
        ResultSet rs = statement.getResultSet();
        if (rs == null) {
          // TODO: The first result is an update count? (see javadoc of getResultSet())
          if (!statement.getMoreResults()) throw new RuntimeException("More results expected");
          rs = statement.getResultSet();
        }
        if (!rs.next()) {
          if (!statement.getMoreResults()) throw new RuntimeException("No result with entity id. SQL is:\n" + sqlToPrepare);
          rs = statement.getResultSet();
          if (!rs.next()) throw new RuntimeException("No result with entity id after trying more results as well");
        }
        EntityId entityId = ofNullable(rs.getObject(1, UUID.class)).map(EntityId.UUID::new).orElse(null);
        statement.getMoreResults();
        rs = statement.getResultSet();
        List<Event> events = new ArrayList<>();
        while (rs.next()) {
          events.add(new Event(
              rs.getInt(1), // EventNumber
              entityModel.eventType(UUID.fromString(rs.getString(2))), // Type
              rs.getObject(3, LocalDateTime.class), // Timestamp
              clock,
              rs.getString(4), // MessageId
              rs.getString(5), // ClientId
              rs.getString(6) // Data
          ));
        }
        List<SecondaryId> secondaryIds = new ArrayList<>();
        for (var idModel2 : entityModel.secondaryIds()) {
          if (!statement.getMoreResults()) throw new IllegalStateException("Expected result for secondary id " + idModel2.name());
          rs = statement.getResultSet();
          if (rs.next()) // Ids might be added with later events
            secondaryIds.add(idModel2.map(rs));
        }
        rs.close();
        return new EventLog(entityModel, entityId, secondaryIds, Collections.unmodifiableList(events));
      }
    })
    .single()
    .onErrorMap(
        e -> e instanceof SQLServerException f && f.getErrorCode() == 50000,
        _ -> new EntityGroupNotInitialised(entityModel, entityGroup)
    ).onErrorMap(
        SQLServerException.class,
        f -> new MssqlException(getClass().getSimpleName(), entityModel, f.getMessage())
    );
  }

}
