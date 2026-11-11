package com.github.thxmasj.backend.core.database.jdbc;

import com.github.thxmasj.backend.core.database.Client.Config;
import com.microsoft.sqlserver.jdbc.SQLServerDataSource;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.pool.HikariPool;
import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.postgresql.ds.PGSimpleDataSource;
import org.postgresql.jdbc.SslMode;

public class DataSourceBuilder {

  private final Config config;

  public DataSourceBuilder(Config config) {
    this.config = config;
  }

  public DataSource build() {
    return switch (config.dialect()) {
      case SQL_SERVER -> loginDataSource(config);
      case POSTGRES -> postgresDataSource(config);
    };
  }

  public DataSource buildPooled() {
    return hikariDataSource(config, build());
  }

  // Configured directly rather than through a separate HikariConfig, which HikariDataSource would copy reflectively
  private HikariDataSource hikariDataSource(Config config, DataSource ssds) {
    HikariDataSource hikariDataSource = new HikariDataSource();
    hikariDataSource.setMaximumPoolSize(config.maximumPoolSize());
    hikariDataSource.setDataSource(ssds);
    // The pool starts on the first connection. Start it now to fail fast, as the HikariDataSource(HikariConfig) constructor does.
    try (Connection _ = hikariDataSource.getConnection()) {
      return hikariDataSource;
    } catch (SQLException | HikariPool.PoolInitializationException e) {
      hikariDataSource.close();
      throw new RuntimeException(
          "Failed to create Hikari data source for database " + config.name() + " on " + config.host() + ":"
              + config.port() + " as " + config.user() + ": " + e.getMessage(),
          e
      );
    }
  }

  private SQLServerDataSource commonDataSource(Config config) {
    SQLServerDataSource ds = new SQLServerDataSource();
    ds.setServerName(config.host());
    ds.setPortNumber(config.port());
    ds.setEncrypt("true");
    ds.setLoginTimeout(30);
    ds.setDatabaseName(config.name());
    ds.setHostNameInCertificate("*.database.windows.net");
    ds.setQueryTimeout((int)config.queryTimeout().toSeconds());
    return ds;
  }

  private DataSource loginDataSource(Config config) {
    SQLServerDataSource ds = commonDataSource(config);
    ds.setTrustServerCertificate(config.trustServerCertificate());
    ds.setUser(config.user());
    ds.setPassword(config.password());
    return ds;
  }

  private DataSource postgresDataSource(Config config) {
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

}
