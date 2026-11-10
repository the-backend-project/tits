package com.github.thxmasj.statemachine.database.jdbc;

import com.github.thxmasj.statemachine.database.Client.Config;
import com.microsoft.sqlserver.jdbc.SQLServerDataSource;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.pool.HikariPool;
import javax.sql.DataSource;

public class DataSourceBuilder {

  private final Config config;

  public DataSourceBuilder(Config config) {
    this.config = config;
  }

  public DataSource build() {
    return loginDataSource(config);
  }

  public DataSource buildPooled() {
    return hikariDataSource(config, build());
  }

  private HikariDataSource hikariDataSource(Config config, DataSource ssds) {
    HikariConfig hikariConfig = new HikariConfig();
    hikariConfig.setMaximumPoolSize(config.maximumPoolSize());
    hikariConfig.setDataSource(ssds);
    try {
      return new HikariDataSource(hikariConfig);
    } catch (HikariPool.PoolInitializationException e) {
      throw new RuntimeException("Failed to create Hikari data source for config " + config + ": " + e.getMessage() + "\n" + System.getenv());
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

}
