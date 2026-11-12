package com.github.thxmasj.backend.database.pgsql.r2dbc;

import com.github.thxmasj.backend.core.database.Row;

record PgsqlR2dbcRow(io.r2dbc.spi.Row row) implements Row {

  @Override
  public <T> T get(String name, Class<T> type) {
    return row.get(name, type);
  }

}
