module com.github.thxmasj.backend.database.pgsql.r2dbc {
  requires transitive com.github.thxmasj.backend.core;
  requires com.github.thxmasj.backend.database.pgsql.jdbc;
  requires r2dbc.spi;
  requires r2dbc.postgresql;
  requires r2dbc.pool;

  exports com.github.thxmasj.backend.database.pgsql.r2dbc;

  provides com.github.thxmasj.backend.core.database.Database with com.github.thxmasj.backend.database.pgsql.r2dbc.PgsqlR2dbcDatabase;
}
