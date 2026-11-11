package com.github.thxmasj.backend.core.database.jdbc;

import com.github.thxmasj.backend.core.database.Client.Config;
import com.github.thxmasj.backend.core.database.Database;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.pool.HikariPool;
import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;

public class DataSourceBuilder {

  private final Database database;
  private final Config config;

  public DataSourceBuilder(Database database, Config config) {
    this.database = database;
    this.config = config;
  }

  public DataSource build() {
    return database.dataSource(config);
  }

  public DataSource buildPooled() {
    return hikariDataSource(config, build());
  }

  // Configured directly rather than through a separate HikariConfig, which HikariDataSource would copy reflectively
  private HikariDataSource hikariDataSource(Config config, DataSource dataSource) {
    HikariDataSource hikariDataSource = new HikariDataSource();
    hikariDataSource.setMaximumPoolSize(config.maximumPoolSize());
    hikariDataSource.setDataSource(dataSource);
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

}
