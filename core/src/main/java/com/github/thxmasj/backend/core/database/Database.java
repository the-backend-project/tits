package com.github.thxmasj.backend.core.database;

import static java.util.stream.Collectors.joining;

import com.github.thxmasj.backend.core.EntityModel;
import com.github.thxmasj.backend.core.State;
import com.github.thxmasj.backend.core.TransitionModelBuilder.TransitionModel;
import com.github.thxmasj.backend.core.database.Client.Config;
import com.github.thxmasj.backend.core.database.jdbc.DataSourceBuilder;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.ServiceLoader.Provider;
import javax.sql.DataSource;
import reactor.core.publisher.Mono;

/// A database the state machine can store its schema in, with the SQL of its dialect and its driver. Databases are
/// provided by modules such as database-mssql, database-pgsql and database-pgsql-reactive, and found on the module path
/// or class path. The schema is created through JDBC, while the state machine's queries go through a [Client], which
/// may use another driver.
public interface Database {

  /// The name the database is selected by, e.g. "mssql"
  String name();

  /// Whether this is the database with the product name reported by the JDBC driver, and so the one used for a data
  /// source by [#of(DataSource)]
  boolean isProduct(String productName);

  /// A data source connecting to the database as configured, without a connection pool
  DataSource dataSource(Config config);

  /// A client of the data source, mapping the database's constraint violations
  Client client(DataSource dataSource);

  /// A client connecting to the database as configured, with a connection pool, which is closed with the client
  default Client client(Config config) {
    return client(new DataSourceBuilder(this, config).buildPooled());
  }

  /// The queries and updates of the schema
  ///
  /// @param client a client of this database
  /// @param transitions the transitions of the state machine, which give the entity models and event types
  Storage storage(
      Client client,
      Map<EntityModel, Map<State, List<TransitionModel<?, ?>>>> transitions,
      String schemaName,
      Clock clock
  );

  /// Creates the schema, or verifies the checksum of the existing one. Completes with 0 if the schema exists.
  Mono<Integer> createSchema(DataSource schemaDataSource, String schemaName, String role);

  /// The databases found on the module path or class path
  static List<Database> all() {
    return ServiceLoader.load(Database.class).stream().map(Provider::get).toList();
  }

  /// The database with the name
  static Database named(String name) {
    return all().stream()
        .filter(database -> database.name().equals(name))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("No database named " + name + " among " + names()));
  }

  /// The database behind the data source, by the product name reported by the JDBC driver
  static Database of(DataSource dataSource) {
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
