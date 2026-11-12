module com.github.thxmasj.backend.database.pgsql.reactive {
  requires transitive com.github.thxmasj.backend.core;
  requires com.github.thxmasj.backend.database.pgsql;
  requires r2dbc.spi;
  requires r2dbc.postgresql;
  requires r2dbc.pool;

  exports com.github.thxmasj.backend.database.pgsql.reactive;

  provides com.github.thxmasj.backend.core.database.Database with com.github.thxmasj.backend.database.pgsql.reactive.PgsqlReactiveDatabase;
}
