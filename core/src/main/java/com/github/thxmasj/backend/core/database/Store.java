package com.github.thxmasj.backend.core.database;

/// An open connection to a [Database], which the state machine stores its event logs through, e.g. a
/// [DatabaseClient] of a database reached through SQL. The storage of the database is found by the [#database()], and
/// runs its queries and updates on the store. A store with a connection pool closes it when it is closed.
public interface Store extends AutoCloseable {

  /// The database this is a connection to
  Database database();

  @Override
  default void close() throws Exception {}

}
