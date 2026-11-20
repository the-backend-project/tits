module com.github.thxmasj.backend.http.inbox {
  requires transitive com.github.thxmasj.backend.http;
  // The claims of an ID token are given as a JWTClaimsSet
  requires transitive com.nimbusds.jose.jwt;
  requires java.net.http;
  requires io.netty.buffer;
  requires io.netty.codec.http;
  requires static jakarta.annotation;

  exports com.github.thxmasj.backend.http.inbox;
}
