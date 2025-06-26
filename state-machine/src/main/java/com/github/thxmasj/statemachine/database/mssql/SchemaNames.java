package com.github.thxmasj.statemachine.database.mssql;

import com.github.thxmasj.statemachine.EntityModel;
import com.github.thxmasj.statemachine.SecondaryId;
import java.sql.ResultSet;
import java.util.List;
import java.util.function.Function;

public record SchemaNames(
  String schema,
  EntityModel model
) {

  public String indexName(List<Column> columns) {
    return String.format("ix%s", String.join("_", columns.stream().map(Column::name).toList()));
  }

  public String idTableName(SecondaryIdModel idModel) {
    return model.name() + "_" + idModel.name();
  }

  public String idTablePrimaryKeyName(SecondaryIdModel idModel) {
    return String.format("pk%s", idTableName(idModel));
  }

  public QualifiedNames qualifiedNames() {
    return new QualifiedNames(this);
  }

  public class QualifiedNames {

    private final SchemaNames entityModel;

    public QualifiedNames(SchemaNames entityModel) {
        this.entityModel = entityModel;
    }

    public String idTable(SecondaryIdModel secondaryId) {
      return qualifiedName(entityModel.idTableName(secondaryId));
    }

    public String qualifiedName(String name) {
      return String.format("[%s].[%s]", schema, name);
    }
  }

  public interface SecondaryIdModel {
    String name();
    List<Column> columns();
    SecondaryId map(ResultSet resultSet);
    default boolean isSerial() {
      return false;
    }
    default Group group() {
      return null;
    }
    interface Group {
      List<Column> groupColumns();
      List<ColumnOrder> groupOrdering();
      boolean isInitial(Object value);
      SecondaryId initial(Object group);
      SecondaryId next(SecondaryId current);
      Object group(Object value);
    }
  }

  public record Column(String name, String type, Function<Object, ?> value) {}
  public record ColumnOrder(Column column, boolean descending) {}

}
