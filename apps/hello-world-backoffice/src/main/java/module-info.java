module com.github.thxmasj.backend.helloworld.backoffice {
  requires com.github.thxmasj.backend.helloworld;
  requires com.github.thxmasj.backend.backoffice;
  // The databases are found by the core with the service loader, as selected by configuration
  requires com.github.thxmasj.backend.database.mssql;
}
