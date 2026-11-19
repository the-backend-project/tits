package com.github.thxmasj.backend.database.pgsql.vertx.internal;

import com.github.thxmasj.backend.database.pgsql.internal.PgsqlStorage;

/// The storage of PostgreSQL reached through its Vert.x client, which is that of PostgreSQL through JDBC
public class PgsqlVertxStorage extends PgsqlStorage {

  @Override
  public String database() {
    return "pgsql-vertx";
  }
}
