package com.github.thxmasj.backend.database.pgsql.r2dbc;

import static java.util.function.Function.identity;
import static java.util.stream.Collectors.joining;

import com.github.thxmasj.backend.core.database.DatabaseClient;
import com.github.thxmasj.backend.core.database.MappingFailure;
import com.github.thxmasj.backend.core.database.Parameter;
import com.github.thxmasj.backend.core.database.Row;
import com.github.thxmasj.backend.database.pgsql.NumberedStatement;
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
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/// A client of PostgreSQL's R2DBC driver, with a connection pool.
///
/// R2DBC runs one statement with parameters at a time, so SQL of several statements, separated by a semicolon at the
/// end of a line, is run statement by statement in a transaction. The rows of the last statement are given, as by
/// [com.github.thxmasj.backend.core.database.jdbc.JDBCClient].
public class PgsqlR2dbcClient implements DatabaseClient {

  private final ConnectionPool pool;

  public PgsqlR2dbcClient(ConnectionPool pool) {
    this.pool = pool;
  }

  /// The pool keeps as many connections as it may have, as the pool of the JDBC driver does
  public static PgsqlR2dbcClient of(Config config) {
    config.requireSqlPassword("pgsql-r2dbc");
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
    return new PgsqlR2dbcClient(new ConnectionPool(ConnectionPoolConfiguration.builder(connectionFactory)
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
    return execute(name, sql, parameters, result -> result.map((row, _) -> rowMapper.apply(new PgsqlR2dbcRow(row))));
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
    List<NumberedStatement> statements = NumberedStatement.of(sql);
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
      NumberedStatement sql,
      Map<String, Parameter<?>> parameters,
      Function<Result, Publisher<T>> extractor
  ) {
    return Flux.from(statement(connection, sql, parameters).execute()).concatMap(extractor);
  }

  private static Statement statement(Connection connection, NumberedStatement sql, Map<String, Parameter<?>> parameters) {
    List<Parameter<?>> values = sql.parameters(parameters);
    //noinspection SqlSourceToSinkFlow
    Statement statement = connection.createStatement(sql.sql());
    for (int i = 0; i < values.size(); i++) {
      if (values.get(i).value() == null)
        statement.bindNull(i, values.get(i).type());
      else
        statement.bind(i, values.get(i).value());
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
