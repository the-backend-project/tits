package com.github.thxmasj.backend.database.pgsql.r2dbc.internal;

import com.github.thxmasj.backend.database.pgsql.internal.PgsqlStorage;

/// The storage of PostgreSQL reached through its R2DBC driver, which is that of PostgreSQL through JDBC
public class PgsqlR2dbcStorage extends PgsqlStorage {

  @Override
  public String database() {
    return "pgsql-r2dbc";
  }
}
