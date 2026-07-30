package com.github.thxmasj.statemachine.database.mssql;

import static com.github.thxmasj.statemachine.database.jdbc.PreparedStatementSupport.prepare;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.joining;

import com.github.thxmasj.statemachine.EntityId;
import com.github.thxmasj.statemachine.EntityModel;
import com.github.thxmasj.statemachine.Event;
import com.github.thxmasj.statemachine.EventLog;
import com.github.thxmasj.statemachine.SecondaryId;
import com.github.thxmasj.statemachine.database.Row;
import com.github.thxmasj.statemachine.database.UnknownEntity;
import com.github.thxmasj.statemachine.database.jdbc.JDBCRow;

import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import javax.sql.DataSource;
import com.github.thxmasj.statemachine.database.mssql.SchemaNames.Column;
import reactor.core.publisher.Mono;

@SuppressWarnings("StringConcatenationInLoop")
public class EventsByEntityId {

  private final DataSource dataSource;
  private final Map<EntityModel, String> sqls;
  private final Map<EntityModel, BiFunction<EntityId, Row, Event<?>>> eventMappers;

  public EventsByEntityId(
      DataSource dataSource,
      List<EntityModel> entityModels,
      String schemaName,
      Map<EntityModel, BiFunction<EntityId, Row, Event<?>>> eventMappers
  ) {
    this.dataSource = dataSource;
    this.sqls = new HashMap<>();
    this.eventMappers = eventMappers;
    for (var entityModel : entityModels) {
      var names = new SchemaNames(schemaName, entityModel);
      String sql =
          """
          SELECT EventNumber, Type, Timestamp, Data
          FROM [{schema}].[Event] WITH (INDEX(pkEvent))
          WHERE EntityId=:entityId
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

  public Mono<EventLog> execute(EntityModel entityModel, EntityId entityId) {
    String sqlToPrepare = requireNonNull(sqls.get(entityModel), "Unknown model " + entityModel.name());
    return Mono.fromCallable(() -> {
      System.out.println("Finding event log for " + entityModel.name() + "/" + entityId.value());
      try (
          var connection = dataSource.getConnection();
          var statement = prepare(sqlToPrepare, Map.of("entityId", entityId.value()), connection)
      ) {
        statement.execute();
        ResultSet rs = statement.getResultSet();
        List<Event<?>> events = new ArrayList<>();
        while (rs.next()) {
          events.add(eventMappers.get(entityModel).apply(entityId, new JDBCRow(rs)));
        }
        List<SecondaryId<?>> secondaryIds = new ArrayList<>();
        for (var idModel : entityModel.secondaryIds()) {
          statement.getMoreResults();
          rs = statement.getResultSet();
          if (rs.next()) // Ids might be added with later events
            secondaryIds.add(idModel.map(rs));
        }
        rs.close();
        if (events.isEmpty() && secondaryIds.isEmpty())
          throw new UnknownEntity(entityModel, entityId);
        return new EventLog(entityModel, entityId, secondaryIds, Collections.unmodifiableList(events));
      }
    });
  }

}
