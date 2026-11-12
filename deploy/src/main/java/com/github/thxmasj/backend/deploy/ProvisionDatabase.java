package com.github.thxmasj.backend.deploy;

import com.microsoft.sqlserver.jdbc.SQLServerDataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Map;

/// Provisions the database for the application: a contained database user for the managed identity the application
/// logs in with, and the role which the migrate admin process grants access to the schema. Run by the deployment with
/// the access token of the server's Microsoft Entra administrator, and can be run any number of times.
///
/// This is the Azure counterpart of the `database/init.sql` of the apps, which provision a local database.
public final class ProvisionDatabase {

  // The user is created with the client id of the managed identity as its SID, and TYPE = E for an Entra application,
  // as FROM EXTERNAL PROVIDER would look the identity up in Microsoft Graph, which the server is not allowed to. A
  // user with another SID, e.g. of an earlier managed identity, is created again.
  private static final String SQL = """
      DECLARE @user sysname = ?, @clientId uniqueidentifier = ?, @role sysname = ?, @sql nvarchar(max), @sid varbinary(16)
      SET @sid = CONVERT(varbinary(16), @clientId)
      IF DATABASE_PRINCIPAL_ID(@user) IS NOT NULL AND (SELECT sid FROM sys.database_principals WHERE name = @user) <> @sid
      BEGIN
          SET @sql = N'DROP USER ' + QUOTENAME(@user)
          EXEC (@sql)
      END
      IF DATABASE_PRINCIPAL_ID(@user) IS NULL
      BEGIN
          SET @sql = N'CREATE USER ' + QUOTENAME(@user) + N' WITH SID = ' + CONVERT(nvarchar(34), @sid, 1) + N', TYPE = E'
          EXEC (@sql)
      END
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
    ds.setAccessToken(required(env, "DATABASE_ACCESS_TOKEN"));
    ds.setEncrypt("true");
    ds.setHostNameInCertificate("*.database.windows.net");
    // A serverless database may be paused, and takes up to a minute to resume
    ds.setLoginTimeout(120);
    String user = required(env, "DATABASE_USER");
    String role = required(env, "DATABASE_ROLE");
    try (Connection connection = ds.getConnection(); PreparedStatement statement = connection.prepareStatement(SQL)) {
      statement.setString(1, user);
      statement.setString(2, required(env, "DATABASE_CLIENT_ID"));
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
