@SuppressWarnings("module")
module com.github.thxmasj.backend.database.pgsql.jdbc {
  requires transitive com.github.thxmasj.backend.core;
  requires org.postgresql.jdbc;

  exports com.github.thxmasj.backend.database.pgsql;
  // Shared with the modules of PostgreSQL's other drivers only. They are not dependencies of this one, so the compiler
  // would warn that they are not found.
  exports com.github.thxmasj.backend.database.pgsql.internal to
      com.github.thxmasj.backend.database.pgsql.r2dbc,
      com.github.thxmasj.backend.database.pgsql.vertx;

  provides com.github.thxmasj.backend.core.database.Database with com.github.thxmasj.backend.database.pgsql.PgsqlDatabase;
}
