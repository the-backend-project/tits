module com.github.thxmasj.backend.database.pgsql.jdbc {
  requires transitive com.github.thxmasj.backend.core;
  requires org.postgresql.jdbc;

  exports com.github.thxmasj.backend.database.pgsql;

  provides com.github.thxmasj.backend.core.database.Database with com.github.thxmasj.backend.database.pgsql.PgsqlDatabase;
}
