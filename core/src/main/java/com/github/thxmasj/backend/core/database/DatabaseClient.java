package com.github.thxmasj.backend.core.database;

import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import com.github.thxmasj.backend.core.EntityModel;
import jakarta.validation.constraints.NotBlank;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/// Runs the queries and updates of the state machine. A client with a connection pool closes it when it is closed.
public interface DatabaseClient extends AutoCloseable {

  default Query.Builder sql(String sql) {
    return new Query.Builder(this).sql(sql);
  }

  <T> Mono<T> one(String name, String sql, Map<String, Parameter<?>> parameters, Function<Row, T> rowMapper);

  <T> Flux<T> all(String name, String sql, Map<String, Parameter<?>> parameters, Function<Row, T> rowMapper);

  Mono<Integer> update(String name, String sql, Map<String, Parameter<?>> parameters);

  @Override
  default void close() throws Exception {}

  /// @param authentication how to authenticate, or null for [Authentication#SqlPassword]
  record Config(
      @NotBlank String host,
      int port,
      @NotBlank String name,
      String user,
      String password,
      boolean trustServerCertificate,
      int maximumPoolSize,
      Duration queryTimeout,
      Authentication authentication
  ) {

    /// How to authenticate to the database. Microsoft Entra ID is for SQL Server (Azure SQL) only.
    public enum Authentication {
      /// With the user and password of a database login or user
      SqlPassword,
      /// As the Microsoft Entra ID service principal with the client id of the user and the client secret of the
      /// password. Needs azure-identity at runtime, which the native images of the apps are built without.
      ActiveDirectoryServicePrincipal,
      /// As the managed identity of the host, the user-assigned one with the client id of the user if given, without
      /// a password. The tokens are fetched from the managed identity endpoint of Azure App Service and Functions, or
      /// else from the Instance Metadata Service, so this works in a native image too.
      ActiveDirectoryManagedIdentity,
      /// With a Microsoft Entra ID access token for Azure SQL in the password, without a user, e.g. that of
      /// `az account get-access-token --resource https://database.windows.net/`. The token is not renewed, so this is
      /// for short-lived processes like migration.
      ActiveDirectoryAccessToken
    }

    /// Reads the configuration from the `DATABASE_*` environment variables, as the apps do:
    ///
    /// - `DATABASE_HOST`, `DATABASE_PORT` and `DATABASE_NAME`: where the database is, by default on the port of the
    ///   `database`, `mssql` or `pgsql`
    /// - `DATABASE_AUTHENTICATION`: one of [Authentication], by default [Authentication#SqlPassword]
    /// - `DATABASE_USER` and `DATABASE_PASSWORD`: those of a SQL login, or the client id and secret of an Entra ID
    ///   service principal, or the user is the optional client id of a managed identity, which has no password
    /// - `DATABASE_ACCESS_TOKEN`: the access token of [Authentication#ActiveDirectoryAccessToken], which has no user
    ///   and password
    /// - `DATABASE_TRUST_SERVER_CERTIFICATE`, `DATABASE_POOL_SIZE` and `DATABASE_QUERY_TIMEOUT_SECONDS`
    public static Config fromEnvironment(Map<String, String> env, String database) {
      Authentication authentication = authentication(env);
      if (database.equals("pgsql") && authentication != Authentication.SqlPassword)
        throw new IllegalArgumentException("Environment variable DATABASE_AUTHENTICATION must be " + Authentication.SqlPassword + " for pgsql");
      if (authentication != Authentication.ActiveDirectoryAccessToken && optional(env, "DATABASE_ACCESS_TOKEN") != null)
        throw new IllegalArgumentException("Environment variable DATABASE_ACCESS_TOKEN must not be set with DATABASE_AUTHENTICATION " + authentication);
      String user = switch (authentication) {
        case SqlPassword, ActiveDirectoryServicePrincipal -> required(env, "DATABASE_USER");
        case ActiveDirectoryManagedIdentity -> optional(env, "DATABASE_USER");
        case ActiveDirectoryAccessToken -> absent(env, "DATABASE_USER", authentication);
      };
      String password = switch (authentication) {
        case SqlPassword, ActiveDirectoryServicePrincipal -> required(env, "DATABASE_PASSWORD");
        case ActiveDirectoryManagedIdentity -> absent(env, "DATABASE_PASSWORD", authentication);
        case ActiveDirectoryAccessToken -> {
          absent(env, "DATABASE_PASSWORD", authentication);
          yield required(env, "DATABASE_ACCESS_TOKEN");
        }
      };
      return new Config(
          required(env, "DATABASE_HOST"),
          Integer.parseInt(env.getOrDefault("DATABASE_PORT", database.equals("pgsql") ? "5432" : "1433")),
          required(env, "DATABASE_NAME"),
          user,
          password,
          Boolean.parseBoolean(env.getOrDefault("DATABASE_TRUST_SERVER_CERTIFICATE", "false")),
          Integer.parseInt(env.getOrDefault("DATABASE_POOL_SIZE", "10")),
          Duration.ofSeconds(Long.parseLong(env.getOrDefault("DATABASE_QUERY_TIMEOUT_SECONDS", "10"))),
          authentication
      );
    }

    private static Authentication authentication(Map<String, String> env) {
      String value = optional(env, "DATABASE_AUTHENTICATION");
      if (value == null)
        return Authentication.SqlPassword;
      try {
        return Authentication.valueOf(value);
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException("Environment variable DATABASE_AUTHENTICATION must be one of " + Arrays.toString(Authentication.values()));
      }
    }

    private static String optional(Map<String, String> env, String name) {
      String value = env.get(name);
      return value == null || value.isBlank() ? null : value;
    }

    private static String required(Map<String, String> env, String name) {
      String value = optional(env, name);
      if (value == null)
        throw new IllegalArgumentException("Environment variable " + name + " is required");
      return value;
    }

    private static String absent(Map<String, String> env, String name, Authentication authentication) {
      if (optional(env, name) != null)
        throw new IllegalArgumentException("Environment variable " + name + " must not be set with DATABASE_AUTHENTICATION " + authentication);
      return null;
    }

    /// Authenticates with the user and password
    public Config(
        String host,
        int port,
        String name,
        String user,
        String password,
        boolean trustServerCertificate,
        int maximumPoolSize,
        Duration queryTimeout
    ) {
      this(host, port, name, user, password, trustServerCertificate, maximumPoolSize, queryTimeout, null);
    }

    public Authentication authentication() {
      return Objects.requireNonNullElse(authentication, Authentication.SqlPassword);
    }

    /// Fails unless the authentication is with the user and password, for the databases which only support that
    public Config requireSqlPassword(String database) {
      if (authentication() != Authentication.SqlPassword)
        throw new IllegalArgumentException("Authentication " + authentication() + " is not supported by " + database + ", only " + Authentication.SqlPassword);
      return this;
    }

    public int maximumPoolSize() {
      return maximumPoolSize != 0 ? maximumPoolSize : 10;
    }

    public Duration queryTimeout() {
      return Objects.requireNonNullElse(queryTimeout, Duration.ofSeconds(30));
    }

  }

  class Query<T> {

    private final DatabaseClient client;
    private final Map<String, Parameter<?>> parameters;
    private final String sql;
    private final String name;
    private Function<Row, T> rowMapper;

    Query(String name, String sql, Map<String, Parameter<?>> parameters, DatabaseClient client, Function<Row, T> rowMapper) {
      this(name, sql, parameters, client);
      this.rowMapper = rowMapper;
    }

    Query(String name, String sql, Map<String, Parameter<?>> parameters, DatabaseClient client) {
      this.name = name;
      this.sql = sql;
      this.parameters = parameters;
      this.client = client;
    }

    public Mono<T> one() {
      return client.one(name, sql, parameters, rowMapper);
    }

    public Flux<T> all() {
      return client.all(name, sql, parameters, rowMapper);
    }

    protected Mono<Integer> update() {
      return client.update(name, sql, parameters);
    }

    public static class Builder {

      private final Map<String, Parameter<?>> parameters = new HashMap<>();
      private final DatabaseClient client;
      private String name;
      private String parameterPrefix;
      private String sql;

      public Builder(DatabaseClient client) {
        this.client = client;
      }

      public Builder sql(String sql) {
        this.sql = sql;
        return this;
      }

      public Builder name(String name) {
        this.name = name;
        return this;
      }

      public Builder parameterPrefix(String parameterPrefix) {
        this.parameterPrefix = parameterPrefix;
        return this;
      }

      public Builder bind(String k, Object v) {
        parameters.put(parameterPrefix != null ? parameterPrefix + k : k, new Parameter<>(v.getClass(), v));
        return this;
      }

      public <T> Builder bindNullable(String k, Class<T> type, T value) {
        parameters.put(parameterPrefix != null ? parameterPrefix + k : k, new Parameter<>(type, value));
        return this;
      }

      public <T> Query<T> map(Function<Row, T> rowMapper) {
        return new Query<>(name, sql, parameters, client, rowMapper);
      }

      public Mono<Integer> update() {
        return new Query<>(name, sql, parameters, client).update();
      }

    }
  }

  class ConcurrencyFailure extends MssqlException {

    public ConcurrencyFailure(String queryName, EntityModel model, String message, Throwable t) {
      super(queryName, model, message, t);
    }

  }

  class DataIntegrityViolation extends MssqlException {

    private final String tableName;

    public DataIntegrityViolation(String queryName, EntityModel model, String tableName, String message, Throwable t) {
      super(queryName, model, message, t);
      this.tableName = tableName;
    }

    public DataIntegrityViolation(String queryName, EntityModel model, String tableName, Throwable e) {
      super(queryName, model, e);
      this.tableName = tableName;
    }

    public String tableName() {
      return tableName;
    }

  }

  class PrimaryKeyConstraintViolation extends DataIntegrityViolation {

    private final String constraintName;
    private final String duplicateKey;

    public PrimaryKeyConstraintViolation(
        String queryName,
        EntityModel model,
        String duplicateKey,
        String tableName,
        String constraintName,
        Throwable cause
    ) {
      super(queryName, model, tableName, "Primary key constraint " + constraintName + " on table " + tableName + " violated ", cause);
      this.constraintName = constraintName;
      this.duplicateKey = duplicateKey;
    }

    public PrimaryKeyConstraintViolation(String queryName, EntityModel model, String tableName) {
      this(queryName, model, null, tableName, null, null);
    }

    public String constraintName() {
      return constraintName;
    }

    public String duplicateKey() {
      return duplicateKey;
    }
  }

  class UniqueIndexConstraintViolation extends DataIntegrityViolation {

    private final String indexName;
    private final String duplicateKey;

    public UniqueIndexConstraintViolation(
        String queryName,
        EntityModel model,
        String duplicateKey,
        String tableName,
        String indexName,
        Throwable cause
    ) {
      super(queryName, model, tableName, cause);
      this.duplicateKey = duplicateKey;
      this.indexName = indexName;
    }

    public String indexName() {
      return indexName;
    }

    public String duplicateKey() {
      return duplicateKey;
    }
  }

  class MssqlException extends RuntimeException {

    public MssqlException(String queryName, EntityModel model, String message, Throwable t) {
      super("Query " + queryName + " failed (model=" + (model != null ? model.name() : "N/A)")  + ")" + (message != null ? ": " + message : ""), t);
    }

    public MssqlException(String queryName, EntityModel model, String message) {
      this(queryName, model, message, null);
    }

    public MssqlException(String queryName, EntityModel model, Throwable e) {
      this(queryName, model, null, e);
    }
  }

  class UncategorizedMssqlException extends MssqlException {

    private final int errorCode;

    public UncategorizedMssqlException(String queryName, EntityModel model, String message, int errorCode, Throwable cause) {
      super(queryName, model, message, cause);
      this.errorCode = errorCode;
    }

    public int getErrorCode() {
      return errorCode;
    }
  }
}
