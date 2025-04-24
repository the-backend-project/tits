package com.github.thxmasj.statemachine.database.mssql;

import static java.util.stream.Collectors.joining;

import com.github.thxmasj.statemachine.EntityModel;
import com.github.thxmasj.statemachine.database.mssql.SchemaNames.Column;
import com.github.thxmasj.statemachine.database.mssql.SchemaNames.SecondaryIdModel;
import com.github.thxmasj.statemachine.SecondaryId;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import reactor.core.publisher.Mono;

public class LastSecondaryId {

  private final DataSource dataSource;
  private final Map<SecondaryIdModel, String> sql = new HashMap<>();

  public LastSecondaryId(
      DataSource dataSource,
      List<EntityModel> entityModels,
      String schemaName
  ) {
    this.dataSource = dataSource;
    for (var entityModel : entityModels) {
      var names = new SchemaNames(schemaName, entityModel);
      for (var idModel : entityModel.secondaryIds()) {
        if (idModel.group() == null) {
          continue;
        }
        this.sql.put(
            idModel,
            """
            SELECT TOP(1) #IdColumns#
            FROM #IdTable# WITH (INDEX(#IdTableIndex#))
            WHERE #GroupFilter#
            ORDER BY #GroupOrdering#
            """
                .replaceAll("#IdColumns#", idModel.columns().stream().map(Column::name).collect(joining(",")))
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
        );
      }
    }
  }

  public Mono<SecondaryId> execute(SecondaryIdModel idModel, Object entityGroup) {
    String sqlToPrepare = sql.get(idModel);
    if (sqlToPrepare == null) throw new IllegalArgumentException(String.format("Model %s for secondary id unknown", idModel.name()));
    return Mono.fromCallable(() -> {
      //noinspection SqlSourceToSinkFlow
      try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(sqlToPrepare)) {
        int i = 0;
        for (; i < idModel.group().groupColumns().size(); i++) {
          statement.setString(i + 1, idModel.group().groupColumns().get(i).value().apply(entityGroup).toString());
        }
        statement.execute();
        ResultSet rs = statement.getResultSet();
        if (!rs.next()) {
          return null; // The resulting Mono completes empty
        }
        return idModel.map(rs);
      }
    });
  }

}
