package com.github.thxmasj.statemachine;

import java.sql.ResultSet;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

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

  record Column(String name, String type, Function<Object, ?> value) {}
  record ColumnOrder(Column column, boolean descending) {}
}
