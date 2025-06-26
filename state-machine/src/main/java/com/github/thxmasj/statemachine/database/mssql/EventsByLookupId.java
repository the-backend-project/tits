package com.github.thxmasj.statemachine.database.mssql;

import static com.github.thxmasj.statemachine.database.mssql.Mappers.eventMapper;
import static java.util.Optional.ofNullable;
import static java.util.stream.Collectors.joining;

import com.github.thxmasj.statemachine.EntityId;
import com.github.thxmasj.statemachine.EntityModel;
import com.github.thxmasj.statemachine.Event;
import com.github.thxmasj.statemachine.EventLog;
import com.github.thxmasj.statemachine.SecondaryId;
import com.github.thxmasj.statemachine.database.UnknownEntity;
import com.github.thxmasj.statemachine.database.jdbc.JDBCRow;
import com.github.thxmasj.statemachine.database.mssql.SchemaNames.Column;
import com.github.thxmasj.statemachine.database.mssql.SchemaNames.SecondaryIdModel;
import com.microsoft.sqlserver.jdbc.SQLServerException;
import java.sql.ResultSet;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import reactor.core.publisher.Mono;

@SuppressWarnings("StringConcatenationInLoop")
public class EventsByLookupId {

  private final DataSource dataSource;
  private final Clock clock;
  private final Map<SecondaryIdModel, String> sql;

  public EventsByLookupId(
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
        var sql = String.format(
            """
            DECLARE @entityId UNIQUEIDENTIFIER;
            
            SELECT TOP(1) @entityId=EntityId
            FROM %s WITH (INDEX(%s))
            WHERE %s
            %s;
            
            IF @@ROWCOUNT < 1
              THROW 50000, 'Unknown entity', 1

            SELECT @entityId;
            
            SELECT EventNumber, Type, Timestamp, MessageId, ClientId, Data
            FROM [{schema}].Event WITH (INDEX(pkEvent))
            WHERE EntityId=@entityId
            ORDER BY EventNumber;
            """.replace("{schema}", schemaName),
            names.qualifiedNames().idTable(idModel),
            names.indexName(idModel.columns()),
            idModel.columns().stream().map(c -> c.name() + "=?").collect(joining(" AND ")),
            idModel.isSerial() ? "ORDER BY SerialNumber DESC" : ""
        );
        // TODO: Can skip the lookup id, as we already have it
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

  public Mono<EventLog> execute(EntityModel entityModel, SecondaryId secondaryId) {
    String sqlToPrepare = sql.get(secondaryId.model());
    if (sqlToPrepare == null) throw new IllegalArgumentException(String.format("Model %s for secondary id unknown", secondaryId.model().name()));
    return Mono.fromCallable(() -> {
          //noinspection SqlSourceToSinkFlow
          try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(sqlToPrepare)) {
            int index = 1;
            for (var column : secondaryId.model().columns()) {
              statement.setObject(index++, column.value().apply(secondaryId.data()));
            }
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
            List<Event> events = new ArrayList<>();
            while (rs.next()) {
              events.add(eventMapper(entityModel, clock).apply(new JDBCRow(rs)));
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
        .onErrorMap(
            e -> e instanceof SQLServerException f && f.getErrorCode() == 50000,
            _ -> new UnknownEntity(secondaryId)
        )
        ;
  }

}
