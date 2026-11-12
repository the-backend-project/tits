package com.github.thxmasj.backend.database.pgsql.vertx;

import com.github.thxmasj.backend.core.EntityModel;
import com.github.thxmasj.backend.core.State;
import com.github.thxmasj.backend.core.TransitionModel;
import com.github.thxmasj.backend.core.database.DatabaseClient;
import com.github.thxmasj.backend.core.database.DatabaseClient.Config;
import com.github.thxmasj.backend.core.database.Database;
import com.github.thxmasj.backend.core.database.Storage;
import com.github.thxmasj.backend.database.pgsql.PgsqlDatabase;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import reactor.core.publisher.Mono;

/// PostgreSQL through the reactive Vert.x client, e.g. for comparing it with its JDBC driver. The queries are those of
/// [PgsqlDatabase], and the schema is created by it through JDBC.
public class PgsqlVertxDatabase implements Database {

  private final PgsqlDatabase pgsql = new PgsqlDatabase();

  @Override
  public String name() {
    return "pgsql-vertx";
  }

  /// A data source is always of the JDBC driver, so it is not for this database but for [PgsqlDatabase]
  @Override
  public boolean isProduct(String productName) {
    return false;
  }

  /// A data source of the JDBC driver, for creating the schema
  @Override
  public DataSource dataSource(Config config) {
    return pgsql.dataSource(config);
  }

  @Override
  public DatabaseClient client(DataSource dataSource) {
    throw new UnsupportedOperationException("A client of " + name() + " is not of a data source, but of a configuration");
  }

  @Override
  public DatabaseClient client(Config config) {
    return PgsqlVertxClient.of(config);
  }

  @Override
  public Storage storage(
      DatabaseClient client,
      Map<EntityModel, Map<State, List<TransitionModel<?, ?>>>> transitions,
      String schemaName,
      Clock clock
  ) {
    return pgsql.storage(client, transitions, schemaName, clock);
  }

  @Override
  public Mono<Integer> createSchema(DataSource schemaDataSource, String schemaName, String role) {
    return pgsql.createSchema(schemaDataSource, schemaName, role);
  }

}
