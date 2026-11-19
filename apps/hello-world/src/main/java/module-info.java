// The backoffice of the application reads its entities with its entity models. It is not a dependency, so the
// compiler would warn that it is not found.
@SuppressWarnings("module")
module com.github.thxmasj.backend.helloworld {
  requires com.github.thxmasj.backend.database.mssql;
  requires com.github.thxmasj.backend.http.inbox;

  exports com.github.thxmasj.backend.helloworld to com.github.thxmasj.backend.helloworld.backoffice;
}
