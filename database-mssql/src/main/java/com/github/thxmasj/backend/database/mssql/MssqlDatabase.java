package com.github.thxmasj.backend.database.mssql;

import com.github.thxmasj.backend.core.database.DatabaseClient;
import com.github.thxmasj.backend.core.database.DatabaseClient.Config;
import com.github.thxmasj.backend.core.database.SqlDatabase;
import com.github.thxmasj.backend.database.mssql.internal.CreateSchema;
import com.github.thxmasj.backend.database.mssql.internal.GrantRead;
import com.microsoft.sqlserver.jdbc.SQLServerDataSource;
import javax.sql.DataSource;
import reactor.core.publisher.Mono;

/// SQL Server, including Azure SQL
public class MssqlDatabase implements SqlDatabase {

  @Override
  public String name() {
    return "mssql";
  }

  @Override
  public boolean isProduct(String productName) {
    return productName.equals("Microsoft SQL Server");
  }

  @Override
  public DataSource dataSource(Config config) {
    SQLServerDataSource ds = new SQLServerDataSource();
    ds.setServerName(config.host());
    ds.setPortNumber(config.port());
    ds.setEncrypt("true");
    ds.setLoginTimeout(30);
    ds.setDatabaseName(config.name());
    ds.setHostNameInCertificate("*.database.windows.net");
    ds.setQueryTimeout((int)config.queryTimeout().toSeconds());
    ds.setTrustServerCertificate(config.trustServerCertificate());
    switch (config.authentication()) {
      case SqlPassword -> {
        ds.setUser(config.user());
        ds.setPassword(config.password());
      }
      case ActiveDirectoryServicePrincipal -> {
        // Needs azure-identity at runtime, which the driver loads when connecting
        if (config.user() == null || config.password() == null)
          throw new IllegalArgumentException("Authentication " + config.authentication() + " needs the client id as user and the client secret as password");
        ds.setAuthentication(config.authentication().name());
        ds.setUser(config.user());
        ds.setPassword(config.password());
      }
      case ActiveDirectoryManagedIdentity -> {
        if (config.password() != null)
          throw new IllegalArgumentException("Authentication " + config.authentication() + " takes no password");
        // The client id of a user-assigned managed identity, or none for the system-assigned one. The tokens are
        // fetched here rather than by the driver, which would need azure-identity for that.
        ds.setAccessTokenCallback(new ManagedIdentityTokens(config.user()));
      }
      case ActiveDirectoryAccessToken -> {
        if (config.user() != null || config.password() == null)
          throw new IllegalArgumentException("Authentication " + config.authentication() + " needs the access token as password, and no user");
        ds.setAccessToken(config.password());
      }
    }
    return ds;
  }

  @Override
  public DatabaseClient client(DataSource dataSource) {
    return new MssqlClient(this, dataSource);
  }

  @Override
  public Mono<Integer> createSchema(DataSource schemaDataSource, String schemaName, String role) {
    return new CreateSchema(schemaName, role).execute(schemaDataSource);
  }

  @Override
  public Mono<Void> grantRead(DataSource schemaDataSource, String schemaName, String role) {
    return new GrantRead(schemaName, role).execute(schemaDataSource);
  }

}
