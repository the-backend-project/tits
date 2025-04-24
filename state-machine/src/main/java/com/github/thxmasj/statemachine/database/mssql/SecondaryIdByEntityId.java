package com.github.thxmasj.statemachine.database.mssql;

import static java.util.stream.Collectors.joining;

import com.github.thxmasj.statemachine.EntityId;
import com.github.thxmasj.statemachine.EntityModel;
import com.github.thxmasj.statemachine.database.mssql.SchemaNames.Column;
import com.github.thxmasj.statemachine.database.mssql.SchemaNames.SecondaryIdModel;
import com.github.thxmasj.statemachine.SecondaryId;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.sql.DataSource;
import reactor.core.publisher.Mono;

public class SecondaryIdByEntityId {

  private final DataSource dataSource;
  private final Map<SecondaryIdModel, String> sql = new HashMap<>();

  public SecondaryIdByEntityId(
      DataSource dataSource,
      List<EntityModel> entityModels,
      String schemaName
  ) {
    this.dataSource = dataSource;
    for (var entityModel : entityModels) {
      var names = new SchemaNames(schemaName, entityModel);
      for (var idModel : entityModel.secondaryIds()) {
        this.sql.put(
            idModel,
            """
            SELECT #IdColumns#
            FROM #IdTable# WITH (INDEX(#IdTablePK#))
            WHERE EntityId=?
            """
                .replaceAll("#IdColumns#", idModel.columns().stream().map(Column::name).collect(joining(",")))
                .replaceAll("#IdTable#", names.qualifiedNames().idTable(idModel))
                .replaceAll("#IdTablePK#", names.idTablePrimaryKeyName(idModel))
        );
      }
    }
  }

  public Mono<SecondaryId> execute(EntityModel entityType, SecondaryIdModel idModel, EntityId entityId) {
    Objects.requireNonNull(entityType);
    Objects.requireNonNull(entityId);
    String sqlToPrepare = sql.get(idModel);
    return Mono.fromCallable(() -> {
          //noinspection SqlSourceToSinkFlow
          try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(sqlToPrepare)) {
            statement.setString(1, entityId.value().toString());
            statement.execute();
            ResultSet rs = statement.getResultSet();
            if (!rs.next())
              throw new IllegalStateException("Empty result set for " + idModel.name());
            return idModel.map(rs);
          }
    })
    .single();
  }

}
