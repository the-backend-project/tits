module com.github.thxmasj.backend.database.mssql {
  requires transitive com.github.thxmasj.backend.core;
  requires com.microsoft.sqlserver.jdbc;
  requires java.net.http;

  exports com.github.thxmasj.backend.database.mssql;

  provides com.github.thxmasj.backend.core.database.Database with com.github.thxmasj.backend.database.mssql.MssqlDatabase;
}
