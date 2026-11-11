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

  exports com.github.thxmasj.backend.core;
  exports com.github.thxmasj.backend.core.database;
  exports com.github.thxmasj.backend.core.database.jdbc;
  exports com.github.thxmasj.backend.core.http;
  exports com.github.thxmasj.backend.core.message.http;

  uses com.github.thxmasj.backend.core.database.Database;
}
