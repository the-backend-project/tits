package com.github.thxmasj.backend.database.pgsql.vertx;

import static java.util.stream.Collectors.joining;

import com.github.thxmasj.backend.core.database.DatabaseClient;
import com.github.thxmasj.backend.core.database.MappingFailure;
import com.github.thxmasj.backend.core.database.Parameter;
import com.github.thxmasj.backend.core.database.Row;
import com.github.thxmasj.backend.database.pgsql.NumberedStatement;
import com.github.thxmasj.backend.database.pgsql.PgsqlClient;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.net.ClientSSLOptions;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.pgclient.PgException;
import io.vertx.pgclient.SslMode;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.SqlConnection;
import io.vertx.sqlclient.Tuple;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/// A client of the Vert.x PostgreSQL client, with a connection pool and a Vert.x instance of its own.
///
/// SQL of several statements, separated by a semicolon at the end of a line, is run in a transaction on one connection.
/// The statements are pipelined, i.e. sent without waiting for the results of the ones before them, so they take a
/// single round trip, as with the JDBC driver. The rows of the last statement are given, as by
/// [com.github.thxmasj.backend.core.database.jdbc.JDBCClient].
public class PgsqlVertxClient implements DatabaseClient {

  private final Vertx vertx;
  private final Pool pool;

  public PgsqlVertxClient(Vertx vertx, Pool pool) {
    this.vertx = vertx;
    this.pool = pool;
  }

  public static PgsqlVertxClient of(Config config) {
    config.requireSqlPassword("pgsql-vertx");
    Vertx vertx = Vertx.vertx();
    var options = new PgConnectOptions()
        .setHost(config.host())
        .setPort(config.port())
        .setDatabase(config.name())
        .setUser(config.user())
        .setPassword(config.password())
        // A statement is only pipelined when it is prepared, so preparing it must not take a round trip of its own
        .setCachePreparedStatements(true)
        .addProperty("statement_timeout", String.valueOf(config.queryTimeout().toMillis()));
    // Trusting the server certificate means encryption without verification, if the server supports it
    if (config.trustServerCertificate())
      options.setSslMode(SslMode.PREFER).setSslOptions(new ClientSSLOptions().setTrustAll(true));
    else
      options.setSslMode(SslMode.VERIFY_FULL).setSslOptions(new ClientSSLOptions().setHostnameVerificationAlgorithm("HTTPS"));
    Pool pool = PgBuilder.pool()
        .with(new PoolOptions().setMaxSize(config.maximumPoolSize()))
        .connectingTo(options)
        .using(vertx)
        .build();
    return new PgsqlVertxClient(vertx, pool);
  }

  @Override
  public <T> Mono<T> one(String name, String sql, Map<String, Parameter<?>> parameters, Function<Row, T> rowMapper) {
    return all(name, sql, parameters, rowMapper).singleOrEmpty();
  }

  @Override
  public <T> Flux<T> all(String name, String sql, Map<String, Parameter<?>> parameters, Function<Row, T> rowMapper) {
    return execute(name, sql, parameters)
        .flatMapIterable(rows -> rows)
        .map(row -> rowMapper.apply(new PgsqlVertxRow(row)));
  }

  @Override
  public Mono<Integer> update(String name, String sql, Map<String, Parameter<?>> parameters) {
    return execute(name, sql, parameters).map(RowSet::rowCount);
  }

  /// Waits for the pool and then Vert.x to close. Vert.x is not closed from the completion of the pool, which is on one
  /// of the event loop threads Vert.x would then wait for.
  @Override
  public void close() throws Exception {
    pool.close().toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
    vertx.close().toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
  }

  /// The rows of the last statement
  private Mono<RowSet<io.vertx.sqlclient.Row>> execute(String name, String sql, Map<String, Parameter<?>> parameters) {
    return Mono.fromCompletionStage(() -> {
          List<NumberedStatement> statements = NumberedStatement.of(sql);
          // The parameters are bound before anything is sent, so that a missing one does not leave a transaction open
          List<Tuple> tuples = statements.stream().map(statement -> tuple(statement.parameters(parameters))).toList();
          Future<RowSet<io.vertx.sqlclient.Row>> result = statements.size() == 1 ?
              pool.preparedQuery(statements.getFirst().sql()).execute(tuples.getFirst()) :
              pool.withConnection(connection -> inTransaction(connection, statements, tuples));
          return result.toCompletionStage();
        })
        .onErrorMap(errorMapper(name, sql, parameters));
  }

  /// Sends BEGIN, the statements and COMMIT at once. If a statement fails, PostgreSQL ignores the ones after it and
  /// rolls back at the COMMIT, so the connection is not left in a transaction. The failure of the first statement that
  /// failed is given, rather than those of the ones ignored after it.
  private static Future<RowSet<io.vertx.sqlclient.Row>> inTransaction(
      SqlConnection connection,
      List<NumberedStatement> statements,
      List<Tuple> tuples
  ) {
    List<Future<?>> all = new ArrayList<>();
    all.add(connection.query("BEGIN").execute());
    List<Future<RowSet<io.vertx.sqlclient.Row>>> results = new ArrayList<>();
    for (int i = 0; i < statements.size(); i++)
      results.add(connection.preparedQuery(statements.get(i).sql()).execute(tuples.get(i)));
    all.addAll(results);
    all.add(connection.query("COMMIT").execute());
    return Future.join(all).transform(_ -> all.stream()
        .filter(Future::failed)
        .findFirst()
        .<Future<RowSet<io.vertx.sqlclient.Row>>>map(failed -> Future.failedFuture(failed.cause()))
        .orElseGet(() -> results.getLast()));
  }

  private static Tuple tuple(List<Parameter<?>> parameters) {
    Tuple tuple = Tuple.tuple();
    for (Parameter<?> parameter : parameters)
      tuple.addValue(parameter.value() instanceof byte[] bytes ? Buffer.buffer(bytes) : parameter.value());
    return tuple;
  }

  private Function<? super Throwable, ? extends Throwable> errorMapper(String queryName, String sql, Map<String, Parameter<?>> parameters) {
    return error -> {
      // Failing to map a row is not a failure of the query
      if (error instanceof MappingFailure)
        return error;
      String message = "Query " + queryName + " failed: " + error.getMessage() + "\nParameters:\n" +
          parameters.entrySet().stream().map(e -> e.getKey() + "\t" + e.getValue()).collect(joining("\n")) +
          "\nSQL:\n" + sql;
      Throwable violation = error instanceof PgException e ?
          PgsqlClient.constraintViolation(e.getSqlState(), e.getDetail(), e.getTable(), e.getConstraint(), queryName, message, error) :
          null;
      return violation != null ? violation : new RuntimeException(message, error);
    };
  }

}
