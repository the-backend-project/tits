package com.github.thxmasj.statemachine.database.jdbc;

import com.github.thxmasj.statemachine.database.Row;

import java.sql.ResultSet;
import java.sql.SQLException;

public class JDBCRow implements Row {

  private final ResultSet resultSet;

  public JDBCRow(ResultSet resultSet) {
    this.resultSet = resultSet;
  }

  @Override
  public <T> T get(String name, Class<T> type) {
    try {
      return resultSet.getObject(name, type);
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }
}
