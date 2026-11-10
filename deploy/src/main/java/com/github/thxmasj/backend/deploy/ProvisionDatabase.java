package com.github.thxmasj.backend.deploy;

import com.microsoft.sqlserver.jdbc.SQLServerDataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Map;

/// Provisions the database for the application: a contained database user for the application to log in with, and
/// the role which the migrate admin process grants access to the schema. Run by the deployment with the server
/// administrator's credentials, and can be run any number of times.
///
/// This is the Azure counterpart of `demo/database/init.sql`, which provisions a local database.
public final class ProvisionDatabase {

  private static final String SQL = """
      DECLARE @user sysname = ?, @password nvarchar(128) = ?, @role sysname = ?, @sql nvarchar(max)
      IF DATABASE_PRINCIPAL_ID(@user) IS NULL
          SET @sql = N'CREATE USER ' + QUOTENAME(@user) + N' WITH PASSWORD = ' + QUOTENAME(@password, '''')
      ELSE
          SET @sql = N'ALTER USER ' + QUOTENAME(@user) + N' WITH PASSWORD = ' + QUOTENAME(@password, '''')
      EXEC (@sql)
      IF DATABASE_PRINCIPAL_ID(@role) IS NULL
      BEGIN
          SET @sql = N'CREATE ROLE ' + QUOTENAME(@role)
          EXEC (@sql)
      END
      IF IS_ROLEMEMBER(@role, @user) = 0
      BEGIN
          SET @sql = N'ALTER ROLE ' + QUOTENAME(@role) + N' ADD MEMBER ' + QUOTENAME(@user)
          EXEC (@sql)
      END
      """;

  private ProvisionDatabase() {}

  public static void main(String[] args) throws SQLException {
    Map<String, String> env = System.getenv();
    SQLServerDataSource ds = new SQLServerDataSource();
    ds.setServerName(required(env, "DATABASE_HOST"));
    ds.setDatabaseName(required(env, "DATABASE_NAME"));
    ds.setUser(required(env, "DATABASE_ADMIN_USER"));
    ds.setPassword(required(env, "DATABASE_ADMIN_PASSWORD"));
    ds.setEncrypt("true");
    ds.setHostNameInCertificate("*.database.windows.net");
    // A serverless database may be paused, and takes up to a minute to resume
    ds.setLoginTimeout(120);
    String user = required(env, "DATABASE_USER");
    String role = required(env, "DATABASE_ROLE");
    try (Connection connection = ds.getConnection(); PreparedStatement statement = connection.prepareStatement(SQL)) {
      statement.setString(1, user);
      statement.setString(2, required(env, "DATABASE_PASSWORD"));
      statement.setString(3, role);
      statement.execute();
    }
    System.out.println("Provisioned user " + user + " with role " + role);
  }

  private static String required(Map<String, String> env, String name) {
    String value = env.get(name);
    if (value == null || value.isBlank())
      throw new IllegalArgumentException("Environment variable " + name + " is required");
    return value;
  }

}
