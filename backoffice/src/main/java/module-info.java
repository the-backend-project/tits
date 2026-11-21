module com.github.thxmasj.backend.backoffice {
  requires transitive com.github.thxmasj.backend.http.inbox;
  requires com.nimbusds.jose.jwt;
  requires java.sql;

  exports com.github.thxmasj.backend.backoffice;
  // The static files are read by the class of the module
  opens com.github.thxmasj.backend.backoffice;
}
