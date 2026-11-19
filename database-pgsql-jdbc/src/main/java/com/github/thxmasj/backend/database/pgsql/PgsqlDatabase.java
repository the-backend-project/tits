package com.github.thxmasj.backend.database.pgsql;

import com.github.thxmasj.backend.core.database.Database;
import com.github.thxmasj.backend.core.database.DatabaseClient;
import com.github.thxmasj.backend.core.database.DatabaseClient.Config;
import com.github.thxmasj.backend.database.pgsql.internal.CreateSchema;
import javax.sql.DataSource;
import org.postgresql.ds.PGSimpleDataSource;
import org.postgresql.jdbc.SslMode;
import reactor.core.publisher.Mono;

/// PostgreSQL
public class PgsqlDatabase implements Database {

  @Override
  public String name() {
    return "pgsql-jdbc";
  }

  @Override
  public boolean isProduct(String productName) {
    return productName.equals("PostgreSQL");
  }

  @Override
  public DataSource dataSource(Config config) {
    config.requireSqlPassword(name());
    PGSimpleDataSource ds = new PGSimpleDataSource();
    ds.setServerNames(new String[] {config.host()});
    ds.setPortNumbers(new int[] {config.port()});
    ds.setDatabaseName(config.name());
    ds.setUser(config.user());
    ds.setPassword(config.password());
    ds.setLoginTimeout(30);
    // Trusting the server certificate means encryption without verification, if the server supports it
    ds.setSslMode((config.trustServerCertificate() ? SslMode.PREFER : SslMode.VERIFY_FULL).value);
    ds.setOptions("-c statement_timeout=" + config.queryTimeout().toMillis());
    return ds;
  }

  @Override
  public DatabaseClient client(DataSource dataSource) {
    return new PgsqlClient(dataSource);
  }

  @Override
  public Mono<Integer> createSchema(DataSource schemaDataSource, String schemaName, String role) {
    return new CreateSchema(schemaName, role).execute(schemaDataSource);
  }

}
