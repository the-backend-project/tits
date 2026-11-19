package com.github.thxmasj.backend.core.internal.database.jdbc;

import com.github.thxmasj.backend.core.database.Row;
import java.sql.ResultSet;
import java.sql.SQLException;

public class JDBCRow implements Row {

  /// Gets a column of a row as an object of a type
  @FunctionalInterface
  public interface ColumnGetter {
    <T> T get(ResultSet resultSet, String name, Class<T> type) throws SQLException;
  }

  private final ResultSet resultSet;
  private final ColumnGetter columnGetter;

  public JDBCRow(ResultSet resultSet, ColumnGetter columnGetter) {
    this.resultSet = resultSet;
    this.columnGetter = columnGetter;
  }

  @Override
  public <T> T get(String name, Class<T> type) {
    try {
      return columnGetter.get(resultSet, name, type);
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }
}
