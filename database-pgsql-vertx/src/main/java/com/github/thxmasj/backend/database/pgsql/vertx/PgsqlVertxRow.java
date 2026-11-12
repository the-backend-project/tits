package com.github.thxmasj.backend.database.pgsql.vertx;

import com.github.thxmasj.backend.core.database.Row;
import io.vertx.core.buffer.Buffer;
import java.util.Locale;
import java.util.NoSuchElementException;

/// A row of the Vert.x client, whose column names are as PostgreSQL gives them, i.e. in lower case unless quoted, and
/// whose values are of the column types, e.g. Short for SMALLINT and Buffer for BYTEA
record PgsqlVertxRow(io.vertx.sqlclient.Row row) implements Row {

  @Override
  public <T> T get(String name, Class<T> type) {
    int index = row.getColumnIndex(name);
    if (index == -1)
      index = row.getColumnIndex(name.toLowerCase(Locale.ROOT));
    if (index == -1)
      throw new NoSuchElementException("No column " + name);
    Object value = row.getValue(index);
    return switch (value) {
      case null -> null;
      case Buffer buffer when type == byte[].class -> type.cast(buffer.getBytes());
      case Number number when type == Integer.class -> type.cast(number.intValue());
      case Number number when type == Long.class -> type.cast(number.longValue());
      default -> type.cast(value);
    };
  }

}
