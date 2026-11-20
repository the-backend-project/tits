/// HTTP messages, clients and the data types of HTTP messages, shared by the HTTP inbox and outbox.
@SuppressWarnings("module")
module com.github.thxmasj.backend.http {
  requires transitive com.github.thxmasj.backend.core;
  requires transitive reactor.netty.core;
  requires transitive reactor.netty.http;
  requires io.netty.buffer;
  requires io.netty.codec.http;
  requires io.netty.handler;
  requires io.netty.resolver;
  requires static jakarta.annotation;
  requires jakarta.validation;
  requires org.hibernate.validator;

  exports com.github.thxmasj.backend.http;

  // Internals shared with the HTTP inbox and outbox only. The modules are not dependencies of this one, so the compiler
  // would warn that they are not found.
  exports com.github.thxmasj.backend.http.internal to
      com.github.thxmasj.backend.http.inbox,
      com.github.thxmasj.backend.http.outbox;
}
