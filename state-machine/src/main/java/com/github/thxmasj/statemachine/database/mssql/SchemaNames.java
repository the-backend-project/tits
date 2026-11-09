package com.github.thxmasj.statemachine.database.mssql;

import com.github.thxmasj.statemachine.EntityModel;
import com.github.thxmasj.statemachine.SecondaryIdModel;
import com.github.thxmasj.statemachine.SecondaryIdModel.Column;
import java.util.List;

public record SchemaNames(
  String schema,
  EntityModel model
) {

  public String indexName(List<Column> columns) {
    return String.format("ix%s", String.join("_", columns.stream().map(Column::name).toList()));
  }

  public String idTableName(SecondaryIdModel<?> idModel) {
    return "Id_" + idModel.name();
  }

  public String idTablePrimaryKeyName(SecondaryIdModel<?> idModel) {
    return String.format("pk%s", idTableName(idModel));
  }

  public QualifiedNames qualifiedNames() {
    return new QualifiedNames();
  }

  public class QualifiedNames {

    public String idTable(SecondaryIdModel<?> secondaryId) {
      return qualifiedName("Id_" + secondaryId.name());
    }

    public String qualifiedName(String name) {
      return String.format("[%s].[%s]", schema, name);
    }
  }
}
