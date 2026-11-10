module com.github.thxmasj.backend.http.inbox {
  requires transitive com.github.thxmasj.backend.core;
  requires transitive reactor.netty.core;
  requires transitive reactor.netty.http;
  requires com.nimbusds.jose.jwt;
  requires io.netty.buffer;
  requires io.netty.codec.http;
  requires static jakarta.annotation;

  exports com.github.thxmasj.statemachine.http.inbox;
}
