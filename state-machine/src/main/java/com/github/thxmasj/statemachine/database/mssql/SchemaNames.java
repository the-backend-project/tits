package com.github.thxmasj.statemachine.database.mssql;

import com.github.thxmasj.statemachine.EntityModel;
import com.github.thxmasj.statemachine.SecondaryId;
import java.sql.ResultSet;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

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

  public interface SecondaryIdModel<T> {
    UUID id();
    String name();
    List<Column> columns();
    SecondaryId<T> map(ResultSet resultSet);
    default boolean isSerial() {
      return false;
    }
    default Group<T> group() {
      return null;
    }
    interface Group<T> {
      List<Column> groupColumns();
      List<ColumnOrder> groupOrdering();
      boolean isInitial(T value);
      SecondaryId<T> initial(Object group);
      SecondaryId<T> next(SecondaryId<T> current);
      Object group(T value);
    }
  }

  public record Column(String name, String type, Function<Object, ?> value) {}
  public record ColumnOrder(Column column, boolean descending) {}

}
