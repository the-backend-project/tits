package com.github.thxmasj.backend.database.mssql.internal;

import java.sql.Connection;
import java.sql.Statement;
import javax.sql.DataSource;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/// Grants a role read access to the tables of a schema of a state machine, and nothing else, e.g. for a backoffice
/// reading the entities of an application with a role of its own
public class GrantRead {

  private final String schema;
  private final String role;

  public GrantRead(String schemaName, String role) {
    this.schema = schemaName;
    this.role = role;
  }

  /// Granting SELECT on the schema covers its tables, also any added to it later
  public Mono<Void> execute(DataSource dataSource) {
    return Mono.<Void>fromCallable(() -> {
          try (Connection connection = dataSource.getConnection(); Statement grant = connection.createStatement()) {
            grant.executeUpdate("GRANT SELECT ON SCHEMA::[" + schema.replace("]", "]]") + "] TO [" + role.replace("]", "]]") + "]");
          }
          return null;
        })
        .subscribeOn(Schedulers.boundedElastic())
        .doOnSuccess(_ -> System.out.println("Granted " + role + " read access to schema " + schema));
  }

}
