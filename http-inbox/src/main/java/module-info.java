module com.github.thxmasj.statemachine.http.inbox {
  requires transitive com.github.thxmasj.statemachine;
  requires transitive reactor.netty.core;
  requires transitive reactor.netty.http;
  requires com.nimbusds.jose.jwt;
  requires io.netty.buffer;
  requires io.netty.codec.http;
  requires static jakarta.annotation;

  exports com.github.thxmasj.statemachine.http.inbox;
}
