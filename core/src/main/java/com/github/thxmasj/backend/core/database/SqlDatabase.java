package com.github.thxmasj.backend.core.database;

import static java.util.stream.Collectors.joining;

import com.github.thxmasj.backend.core.database.DatabaseClient.Config;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import javax.sql.DataSource;
import reactor.core.publisher.Mono;

/// A database reached through SQL, with the SQL of its dialect and its driver. The schema is created through JDBC,
/// while the state machine's queries go through a [DatabaseClient], which may use another driver.
public interface SqlDatabase extends Database {

  /// Whether this is the database with the product name reported by the JDBC driver, and so the one used for a data
  /// source by [#of(DataSource)]
  boolean isProduct(String productName);

  /// A data source connecting to the database as configured, without a connection pool
  DataSource dataSource(Config config);

  /// A client of the data source, mapping the database's constraint violations
  DatabaseClient client(DataSource dataSource);

  /// A client connecting to the database as configured, with a connection pool, which is closed with the client
  default DatabaseClient client(Config config) {
    return client(new DataSourceBuilder(this, config).buildPooled());
  }

  /// Creates the schema, or verifies the checksum of the existing one. Completes with 0 if the schema exists.
  Mono<Integer> createSchema(DataSource schemaDataSource, String schemaName, String role);

  /// Grants the role read access to the tables of an existing schema, and nothing else. Run as an administrator, e.g.
  /// by the migrate admin process of a backoffice, whose role reads the schema of an application.
  Mono<Void> grantRead(DataSource schemaDataSource, String schemaName, String role);

  /// The databases reached through SQL found on the module path or class path
  static List<SqlDatabase> all() {
    return Database.all().stream()
        .filter(SqlDatabase.class::isInstance)
        .map(SqlDatabase.class::cast)
        .toList();
  }

  /// The database reached through SQL with the name
  static SqlDatabase named(String name) {
    if (!(Database.named(name) instanceof SqlDatabase database))
      throw new IllegalArgumentException("Database " + name + " is not reached through SQL, as only " + names() + " are");
    return database;
  }

  /// The database behind the data source, by the product name reported by the JDBC driver
  static SqlDatabase of(DataSource dataSource) {
    String productName;
    try (Connection connection = dataSource.getConnection()) {
      productName = connection.getMetaData().getDatabaseProductName();
    } catch (SQLException e) {
      throw new RuntimeException("Failed to detect database: " + e.getMessage(), e);
    }
    return all().stream()
        .filter(database -> database.isProduct(productName))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("Unsupported database " + productName + ", as only " + names() + " are found"));
  }

  private static String names() {
    return all().stream().map(Database::name).collect(joining(", ", "[", "]"));
  }

}
