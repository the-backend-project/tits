package com.github.thxmasj.backend.core.internal.database.sql;

import com.github.thxmasj.backend.core.database.DatabaseClient;
import com.github.thxmasj.backend.core.database.Store;
import com.github.thxmasj.backend.core.internal.ResolvedModels;
import com.github.thxmasj.backend.core.internal.database.Storage;
import com.github.thxmasj.backend.core.internal.database.StorageProvider;
import com.github.thxmasj.backend.core.internal.database.TestStorage;
import java.time.Clock;

/// The storage of a database reached through SQL, whose store is a [DatabaseClient]
public abstract class SqlStorageProvider implements StorageProvider {

  @Override
  public Storage storage(Store store, ResolvedModels models, String schemaName, Clock clock) {
    return storage(client(store), models, schemaName, clock);
  }

  /// The queries and updates of the state machine in the dialect of the database
  ///
  /// @param client a client of the database
  /// @param models the entity models the state machine runs, with their event types
  protected abstract Storage storage(DatabaseClient client, ResolvedModels models, String schemaName, Clock clock);

  /// The queries and updates of the test API, whose SQL works in the dialects of all the databases
  @Override
  public TestStorage testStorage(Store store, ResolvedModels models, String schemaName, Clock clock) {
    return new SqlTestStorage(client(store), models, schemaName, clock);
  }

  private DatabaseClient client(Store store) {
    if (!(store instanceof DatabaseClient client))
      throw new IllegalArgumentException("The store of database " + database() + " is not a client of SQL, but a " + store.getClass().getName());
    return client;
  }

}
