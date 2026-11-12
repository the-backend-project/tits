package com.github.thxmasj.backend.database.pgsql.reactive;

import static java.util.function.Function.identity;
import static java.util.stream.Collectors.joining;

import com.github.thxmasj.backend.core.database.Client;
import com.github.thxmasj.backend.core.database.MappingFailure;
import com.github.thxmasj.backend.core.database.Parameter;
import com.github.thxmasj.backend.core.database.Row;
import com.github.thxmasj.backend.database.pgsql.PgsqlClient;
import io.r2dbc.pool.ConnectionPool;
import io.r2dbc.pool.ConnectionPoolConfiguration;
import io.r2dbc.postgresql.PostgresqlConnectionConfiguration;
import io.r2dbc.postgresql.PostgresqlConnectionFactory;
import io.r2dbc.postgresql.api.ErrorDetails;
import io.r2dbc.postgresql.api.PostgresqlException;
import io.r2dbc.postgresql.client.SSLMode;
import io.r2dbc.spi.Connection;
import io.r2dbc.spi.Result;
import io.r2dbc.spi.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/// A client of PostgreSQL's R2DBC driver, with a connection pool.
///
/// R2DBC runs one statement with parameters at a time, so SQL of several statements, separated by a semicolon at the
/// end of a line, is run statement by statement in a transaction. The rows of the last statement are given, as by
/// [com.github.thxmasj.backend.core.database.jdbc.JDBCClient].
public class PgsqlReactiveClient implements Client {

  private static final Pattern parameterPattern = Pattern.compile(":[a-zA-Z0-9_]+");
  private static final Pattern statementEnd = Pattern.compile(";[ \\t]*(?:\\n|$)");

  private final ConnectionPool pool;

  public PgsqlReactiveClient(ConnectionPool pool) {
    this.pool = pool;
  }

  /// The pool keeps as many connections as it may have, as the pool of the JDBC driver does
  public static PgsqlReactiveClient of(Config config) {
    var connectionFactory = new PostgresqlConnectionFactory(PostgresqlConnectionConfiguration.builder()
        .host(config.host())
        .port(config.port())
        .database(config.name())
        .username(config.user())
        .password(config.password())
        .connectTimeout(Duration.ofSeconds(30))
        // Trusting the server certificate means encryption without verification, if the server supports it
        .sslMode(config.trustServerCertificate() ? SSLMode.PREFER : SSLMode.VERIFY_FULL)
        .options(Map.of("statement_timeout", String.valueOf(config.queryTimeout().toMillis())))
        .build());
    return new PgsqlReactiveClient(new ConnectionPool(ConnectionPoolConfiguration.builder(connectionFactory)
        .initialSize(config.maximumPoolSize())
        .maxSize(config.maximumPoolSize())
        .build()));
  }

  @Override
  public <T> Mono<T> one(String name, String sql, Map<String, Parameter<?>> parameters, Function<Row, T> rowMapper) {
    return all(name, sql, parameters, rowMapper).singleOrEmpty();
  }

  @Override
  public <T> Flux<T> all(String name, String sql, Map<String, Parameter<?>> parameters, Function<Row, T> rowMapper) {
    return execute(name, sql, parameters, result -> result.map((row, _) -> rowMapper.apply(new PgsqlReactiveRow(row))));
  }

  @Override
  public Mono<Integer> update(String name, String sql, Map<String, Parameter<?>> parameters) {
    return execute(name, sql, parameters, Result::getRowsUpdated)
        .reduce(0L, Long::sum)
        .map(Math::toIntExact);
  }

  @Override
  public void close() {
    pool.dispose();
  }

  /// The values extracted from the results of the last statement
  private <T> Flux<T> execute(
      String name,
      String sql,
      Map<String, Parameter<?>> parameters,
      Function<Result, Publisher<T>> extractor
  ) {
    List<String> statements = statements(sql);
    return Flux.usingWhen(
            pool.create(),
            connection -> statements.size() == 1 ?
                results(connection, statements.getFirst(), parameters, extractor) :
                inTransaction(connection, Flux.fromIterable(statements)
                    .concatMap(statement -> results(connection, statement, parameters, extractor).collectList())
                    .last()
                    .flatMapIterable(identity())),
            Connection::close
        )
        .onErrorMap(errorMapper(name, sql, parameters));
  }

  private static <T> Flux<T> inTransaction(Connection connection, Flux<T> work) {
    return Flux.usingWhen(
        Mono.from(connection.beginTransaction()).thenReturn(connection),
        _ -> work,
        Connection::commitTransaction,
        (c, _) -> c.rollbackTransaction(),
        Connection::rollbackTransaction
    );
  }

  private static <T> Flux<T> results(
      Connection connection,
      String sql,
      Map<String, Parameter<?>> parameters,
      Function<Result, Publisher<T>> extractor
  ) {
    return Flux.from(statement(connection, sql, parameters).execute()).concatMap(extractor);
  }

  /// The statements of the SQL, without their terminating semicolons
  static List<String> statements(String sql) {
    return Arrays.stream(statementEnd.split(sql)).filter(statement -> !statement.isBlank()).toList();
  }

  /// The statement with its named parameters, such as ":entityId", replaced by PostgreSQL's numbered ones, such as "$1"
  private static Statement statement(Connection connection, String sql, Map<String, Parameter<?>> parameters) {
    List<String> names = new ArrayList<>();
    String numbered = parameterPattern.matcher(sql).replaceAll(match -> {
      String name = match.group().substring(1);
      if (!names.contains(name))
        names.add(name);
      return Matcher.quoteReplacement("$" + (names.indexOf(name) + 1));
    });
    //noinspection SqlSourceToSinkFlow
    Statement statement = connection.createStatement(numbered);
    for (int i = 0; i < names.size(); i++) {
      Parameter<?> parameter = parameters.get(names.get(i));
      if (parameter == null)
        throw new IllegalStateException("Parameter " + names.get(i) + " not found in " + parameters.keySet());
      if (parameter.value() == null)
        statement.bindNull(i, parameter.type());
      else
        statement.bind(i, parameter.value());
    }
    return statement;
  }

  private Function<? super Throwable, ? extends Throwable> errorMapper(String queryName, String sql, Map<String, Parameter<?>> parameters) {
    return error -> {
      // Failing to map a row is not a failure of the query
      if (error instanceof MappingFailure)
        return error;
      String message = "Query " + queryName + " failed: " + error.getMessage() + "\nParameters:\n" +
          parameters.entrySet().stream().map(e -> e.getKey() + "\t" + e.getValue()).collect(joining("\n")) +
          "\nSQL:\n" + sql;
      Throwable violation = null;
      if (error instanceof PostgresqlException e) {
        ErrorDetails details = e.getErrorDetails();
        violation = PgsqlClient.constraintViolation(
            details.getCode(),
            details.getDetail().orElse(null),
            details.getTableName().orElse(null),
            details.getConstraintName().orElse(null),
            queryName,
            message,
            error
        );
      }
      return violation != null ? violation : new RuntimeException(message, error);
    };
  }

}
