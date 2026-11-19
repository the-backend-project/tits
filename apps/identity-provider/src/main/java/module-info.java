// The backoffice of the application reads its entities with its entity models. It is not a dependency, so the
// compiler would warn that it is not found.
@SuppressWarnings("module")
module com.github.thxmasj.backend.identityprovider {
  requires com.github.thxmasj.backend.http.inbox;
  requires com.nimbusds.jose.jwt;
  // The upstream OpenID Connect provider is called with the HTTP client of the JDK
  requires java.net.http;
  requires spring.security.crypto;
  // The databases are found by the core with the service loader, as selected by configuration
  requires com.github.thxmasj.backend.database.mssql;
  requires com.github.thxmasj.backend.database.pgsql.jdbc;

  exports com.github.thxmasj.backend.identityprovider to com.github.thxmasj.backend.identityprovider.backoffice;
}
