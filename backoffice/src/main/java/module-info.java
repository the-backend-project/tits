module com.github.thxmasj.backend.backoffice {
  requires transitive com.github.thxmasj.backend.http.inbox;
  // The code of a sign-in is redeemed by an outbox request
  requires com.github.thxmasj.backend.http.outbox;
  requires com.nimbusds.jose.jwt;
  // The OpenID Connect provider is called with the HTTP client of the JDK
  requires java.net.http;
  requires java.sql;

  exports com.github.thxmasj.backend.backoffice;
  // The static files are read by the class of the module
  opens com.github.thxmasj.backend.backoffice;
}
