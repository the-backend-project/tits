@SuppressWarnings("module")
module com.github.thxmasj.backend.core {
  requires transitive tools.jackson.databind;
  requires transitive java.sql;
  requires transitive org.reactivestreams;
  requires transitive reactor.core;
  requires com.zaxxer.hikari;
  requires static jakarta.annotation;
  requires jakarta.validation;

  // The API of applications
  exports com.github.thxmasj.backend.core;
  exports com.github.thxmasj.backend.core.database;

  // Internals shared with the other modules of the library only. The modules are not dependencies of this one, so the
  // compiler would warn that they are not found.
  exports com.github.thxmasj.backend.core.internal to
      com.github.thxmasj.backend.backoffice,
      com.github.thxmasj.backend.database.mssql,
      com.github.thxmasj.backend.database.pgsql.jdbc,
      com.github.thxmasj.backend.http,
      com.github.thxmasj.backend.http.inbox,
      com.github.thxmasj.backend.http.outbox,
      com.github.thxmasj.backend.testapi;
  exports com.github.thxmasj.backend.core.internal.database to
      com.github.thxmasj.backend.database.mssql,
      com.github.thxmasj.backend.database.pgsql.jdbc,
      com.github.thxmasj.backend.database.pgsql.r2dbc,
      com.github.thxmasj.backend.database.pgsql.vertx,
      com.github.thxmasj.backend.testapi;
  exports com.github.thxmasj.backend.core.internal.database.sql to
      com.github.thxmasj.backend.database.mssql,
      com.github.thxmasj.backend.database.pgsql.jdbc,
      com.github.thxmasj.backend.database.pgsql.r2dbc,
      com.github.thxmasj.backend.database.pgsql.vertx;
  exports com.github.thxmasj.backend.core.internal.database.jdbc to
      com.github.thxmasj.backend.database.mssql,
      com.github.thxmasj.backend.database.pgsql.jdbc;

  uses com.github.thxmasj.backend.core.database.Database;
  uses com.github.thxmasj.backend.core.internal.database.StorageProvider;
}
