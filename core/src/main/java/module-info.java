@SuppressWarnings("module")
module com.github.thxmasj.backend.core {
  requires transitive tools.jackson.databind;
  requires transitive java.sql;
  requires transitive org.reactivestreams;
  requires transitive reactor.core;
  requires transitive reactor.netty.core;
  requires transitive reactor.netty.http;
  requires com.zaxxer.hikari;
  requires io.netty.buffer;
  requires io.netty.codec.http;
  requires io.netty.handler;
  requires io.netty.resolver;
  requires static jakarta.annotation;
  requires jakarta.validation;
  requires org.hibernate.validator;

  // The API of applications
  exports com.github.thxmasj.backend.core;
  exports com.github.thxmasj.backend.core.database;
  exports com.github.thxmasj.backend.core.http;
  exports com.github.thxmasj.backend.core.message.http;

  // Internals shared with the other modules of the library only. The modules are not dependencies of this one, so the
  // compiler would warn that they are not found.
  exports com.github.thxmasj.backend.core.internal to
      com.github.thxmasj.backend.backoffice,
      com.github.thxmasj.backend.database.mssql,
      com.github.thxmasj.backend.database.pgsql.jdbc,
      com.github.thxmasj.backend.http.inbox,
      com.github.thxmasj.backend.http.outbox,
      com.github.thxmasj.backend.testapi;
  exports com.github.thxmasj.backend.core.internal.database to
      com.github.thxmasj.backend.database.mssql,
      com.github.thxmasj.backend.database.pgsql.jdbc,
      com.github.thxmasj.backend.database.pgsql.r2dbc,
      com.github.thxmasj.backend.database.pgsql.vertx,
      com.github.thxmasj.backend.testapi;
  exports com.github.thxmasj.backend.core.internal.database.jdbc to
      com.github.thxmasj.backend.database.mssql,
      com.github.thxmasj.backend.database.pgsql.jdbc;
  exports com.github.thxmasj.backend.core.internal.http to
      com.github.thxmasj.backend.http.inbox,
      com.github.thxmasj.backend.http.outbox;

  uses com.github.thxmasj.backend.core.database.Database;
  uses com.github.thxmasj.backend.core.internal.database.StorageProvider;
}
