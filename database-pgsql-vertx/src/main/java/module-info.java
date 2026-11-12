module com.github.thxmasj.backend.database.pgsql.vertx {
  requires transitive com.github.thxmasj.backend.core;
  requires com.github.thxmasj.backend.database.pgsql;
  requires io.vertx.core;
  requires io.vertx.sql.client;
  requires io.vertx.sql.client.pg;

  exports com.github.thxmasj.backend.database.pgsql.vertx;

  provides com.github.thxmasj.backend.core.database.Database with com.github.thxmasj.backend.database.pgsql.vertx.PgsqlVertxDatabase;
}
