package com.github.thxmasj.backend.core.database;

import java.time.Duration;
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

    /// How to authenticate to the database. Microsoft Entra ID is for SQL Server (Azure SQL) only, and needs
    /// azure-identity at runtime.
    public enum Authentication {
      /// With the user and password of a database login or user
      SqlPassword,
      /// As the Microsoft Entra ID service principal with the client id of the user and the client secret of the
      /// password
      ActiveDirectoryServicePrincipal,
      /// As the managed identity of the host, the user-assigned one with the client id of the user if given, without
      /// a password
      ActiveDirectoryManagedIdentity
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
