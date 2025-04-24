package com.github.thxmasj.statemachine.database.jdbc;

import static com.github.thxmasj.statemachine.database.jdbc.PreparedStatementSupport.prepare;
import static java.util.stream.Collectors.joining;

import com.github.thxmasj.statemachine.database.Client;
import com.github.thxmasj.statemachine.database.Row;
import com.microsoft.sqlserver.jdbc.SQLServerException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

public class JDBCClient implements Client {

  private final DataSource dataSource;
  private final Scheduler scheduler = Schedulers.fromExecutorService(Executors.newVirtualThreadPerTaskExecutor());

  public JDBCClient(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  @Override
  public <T> Mono<T> one(String name, String sql, Map<String, Object> parameters, Function<Row, T> rowMapper) {
    return all(name, sql, parameters, rowMapper).singleOrEmpty();
  }

  @Override
  public <T> Flux<T> all(String name, String sql, Map<String, Object> parameters, Function<Row, T> rowMapper) {
    return Mono.fromCallable(() -> {
          try (
              Connection connection = dataSource.getConnection();
              PreparedStatement statement = prepare(sql, parameters, connection)
          ) {
            return mapResult(statement, rowMapper);
          }
        })
        .flatMapMany(Flux::fromIterable)
        .onErrorMap(errorMapper(name, sql, parameters))
        .subscribeOn(scheduler);
  }

  @Override
  public Mono<Integer> update(String name, String sql, Map<String, Object> parameters) {
    return Mono.fromCallable(() -> {
          try (
              Connection connection = dataSource.getConnection();
              PreparedStatement statement = prepare(sql, parameters, connection)
          ){
            return statement.executeUpdate();
          }
        })
        .onErrorMap(errorMapper(name, sql, parameters))
        .subscribeOn(scheduler);
  }

  /**
   * Extract the last result set if the result is "complex", i.e. has multiple result sets and/or update counts. This
   * behaviour is similar to the R2DBCClient's behavior.
   * <p>
   * Note that the SQL Server driver does not support java.sql.Statement.getMoreResults(int), which means every result
   * set must be extracted. That might fail if extractor is not compatible with any of the results.
   */
  private <T> List<T> mapResult(PreparedStatement statement, Function<Row, T> rowMapper) throws SQLException {
    // Inspired by https://learn.microsoft.com/en-us/sql/connect/jdbc/handling-complex-statements?view=sql-server-ver16
    List<T> lastResult = new ArrayList<>();
    boolean results = statement.execute();
    int count = 0;
    do {
      if (results) {
        try (ResultSet rs = statement.getResultSet()) {
          while (rs.next()) {
            lastResult.add(rowMapper.apply(new JDBCRow(rs)));
          }
        }
      } else {
        count = statement.getUpdateCount();
      }
      results = statement.getMoreResults(); // This closes current result set
    } while (results || count != -1);
    return lastResult;
  }

  private Function<? super Throwable, ? extends Throwable> errorMapper(String queryName, String sql, Map<String, Object> parameters) {
    return error -> switch (error) {
      case SQLServerException e when e.getSQLServerError().getErrorNumber() == 2627 -> {
        Matcher primaryKeyViolationMatcher = primaryKeyViolationPattern.matcher(e.getMessage());
        if (primaryKeyViolationMatcher.matches())
          yield new PrimaryKeyConstraintViolation(queryName, null, primaryKeyViolationMatcher.group(3), primaryKeyViolationMatcher.group(1), e);
        else
          yield new DataIntegrityViolation(queryName, null, "N/A", errorMessage(e.getMessage(), queryName, sql, parameters), e);
      }
      case SQLServerException f when f.getSQLServerError().getErrorNumber() == 2601 -> {
        Matcher duplicateKeyRowMatcher = duplicateKeyRowPattern.matcher(f.getMessage());
        if (duplicateKeyRowMatcher.matches())
          yield new UniqueIndexConstraintViolation(queryName, null, duplicateKeyRowMatcher.group(2), duplicateKeyRowMatcher.group(3), f);
        else
          yield new DataIntegrityViolation(queryName, null, "N/A", errorMessage(f.getMessage(), queryName, sql, parameters), f);
      }
      case Throwable e -> new RuntimeException(errorMessage(e.getMessage(), queryName, sql, parameters), error);
    };
  }

  private String errorMessage(String message, String queryName, String sql, Map<String, Object> parameters) {
    return "Query "+queryName+" failed: " + message + "\nParameters:\n" + parameters.entrySet().stream().map(e -> e.getKey() + "\t" + (e.getValue() != null ? e.getValue().getClass().getSimpleName() : "N/A") + "\t" + e.getValue()).collect(joining("\n")) + "\nSQL:\n" + sql;
  }

  private static final Pattern duplicateKeyRowPattern = Pattern.compile(
      "Cannot insert duplicate key row in object '(.*)\\.(.*)' with unique index '(.*)'. The duplicate key value is \\((.*), (.*)\\).");
  private static final Pattern primaryKeyViolationPattern = Pattern.compile(
      "Violation of PRIMARY KEY constraint '(.*)'. Cannot insert duplicate key in object '(.*)\\.(.*)'. The duplicate key value is \\((.*)\\).");

}

