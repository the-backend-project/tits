package com.github.thxmasj.backend.database.pgsql.r2dbc;

import com.github.thxmasj.backend.core.EntityModel;
import com.github.thxmasj.backend.core.State;
import com.github.thxmasj.backend.core.TransitionModelBuilder.TransitionModel;
import com.github.thxmasj.backend.core.database.Client;
import com.github.thxmasj.backend.core.database.Client.Config;
import com.github.thxmasj.backend.core.database.Database;
import com.github.thxmasj.backend.core.database.Storage;
import com.github.thxmasj.backend.database.pgsql.PgsqlDatabase;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import reactor.core.publisher.Mono;

/// PostgreSQL through its R2DBC driver, e.g. for comparing it with its JDBC driver. The queries are those of
/// [PgsqlDatabase], and the schema is created by it through JDBC.
public class PgsqlR2dbcDatabase implements Database {

  private final PgsqlDatabase pgsql = new PgsqlDatabase();

  @Override
  public String name() {
    return "pgsql-r2dbc";
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
  public Client client(DataSource dataSource) {
    throw new UnsupportedOperationException("A client of " + name() + " is not of a data source, but of a configuration");
  }

  @Override
  public Client client(Config config) {
    return PgsqlR2dbcClient.of(config);
  }

  @Override
  public Storage storage(
      Client client,
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
