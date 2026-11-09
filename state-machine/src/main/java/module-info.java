module com.github.thxmasj.statemachine {
  requires transitive tools.jackson.databind;
  requires transitive java.sql;
  requires transitive org.reactivestreams;
  requires transitive reactor.core;
  requires transitive reactor.netty.core;
  requires transitive reactor.netty.http;
  requires com.microsoft.sqlserver.jdbc;
  requires com.zaxxer.hikari;
  requires io.netty.buffer;
  requires io.netty.codec.http;
  requires io.netty.handler;
  requires io.netty.resolver;
  requires static jakarta.annotation;
  requires jakarta.validation;
  requires org.hibernate.validator;

  exports com.github.thxmasj.statemachine;
  exports com.github.thxmasj.statemachine.database;
  exports com.github.thxmasj.statemachine.database.jdbc;
  exports com.github.thxmasj.statemachine.database.mssql;
  exports com.github.thxmasj.statemachine.http;
  exports com.github.thxmasj.statemachine.message.http;
}
