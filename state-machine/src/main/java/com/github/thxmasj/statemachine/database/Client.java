package com.github.thxmasj.statemachine.database;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import com.github.thxmasj.statemachine.EntityModel;
import jakarta.validation.constraints.NotBlank;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface Client {

  default Query.Builder sql(String sql) {
    return new Query.Builder(this).sql(sql);
  }

  <T> Mono<T> one(String name, String sql, Map<String, Object> parameters, Function<Row, T> rowMapper);

  <T> Flux<T> all(String name, String sql, Map<String, Object> parameters, Function<Row, T> rowMapper);

  Mono<Integer> update(String name, String sql, Map<String, Object> parameters);

  record Config(
      @NotBlank String host,
      int port,
      @NotBlank String name,
      String user,
      String password,
      boolean trustServerCertificate,
      int maximumPoolSize,
      Duration queryTimeout
  ) {

    public int maximumPoolSize() {
      return maximumPoolSize != 0 ? maximumPoolSize : 10;
    }

    public Duration queryTimeout() {
      return Objects.requireNonNullElse(queryTimeout, Duration.ofSeconds(30));
    }

  }

  class Query<T> {

    private final Client client;
    private final Map<String, Object> parameters;
    private final String sql;
    private final String name;
    private Function<Row, T> rowMapper;

    Query(String name, String sql, Map<String, Object> parameters, Client client, Function<Row, T> rowMapper) {
      this(name, sql, parameters, client);
      this.rowMapper = rowMapper;
    }

    Query(String name, String sql, Map<String, Object> parameters, Client client) {
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

      private final Map<String, Object> parameters = new HashMap<>();
      private final Client client;
      private String name;
      private String parameterPrefix;
      private String sql;

      public Builder(Client client) {
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
        parameters.put(parameterPrefix != null ? parameterPrefix + k : k, v);
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

    public PrimaryKeyConstraintViolation(
        String queryName,
        EntityModel model,
        String tableName,
        String constraintName,
        Throwable cause
    ) {
      super(queryName, model, tableName, "Primary key constraint " + constraintName + " on table " + tableName + " violated ", cause);
      this.constraintName = constraintName;
    }

    public PrimaryKeyConstraintViolation(String queryName, EntityModel model, String tableName) {
      this(queryName, model, tableName, null, null);
    }

    public String constraintName() {
      return constraintName;
    }
  }

  class UniqueIndexConstraintViolation extends DataIntegrityViolation {

    private final String indexName;

    public UniqueIndexConstraintViolation(
        String queryName,
        EntityModel model,
        String tableName,
        String indexName,
        Throwable cause
    ) {
      super(queryName, model, tableName, cause);
      this.indexName = indexName;
    }

    public String indexName() {
      return indexName;
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
