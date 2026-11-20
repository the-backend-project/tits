package com.github.thxmasj.backend.core.internal.database;

import com.github.thxmasj.backend.core.database.Database;
import com.github.thxmasj.backend.core.database.Store;
import com.github.thxmasj.backend.core.internal.ResolvedModels;
import java.time.Clock;
import java.util.ServiceLoader;
import java.util.ServiceLoader.Provider;

/// The storage of a [Database]. The module of a database provides its storage as a service of its own, found on the
/// module path or class path like the [Database], so that the public [Database] has no internal types.
public interface StorageProvider {

  /// The name of the database this is the storage of, see [Database#name()]
  String database();

  /// The queries and updates of the state machine
  ///
  /// @param store a store of the database, which the queries and updates run on
  /// @param models the entity models the state machine runs, with their event types
  Storage storage(Store store, ResolvedModels models, String schemaName, Clock clock);

  /// The queries and updates of the test API
  ///
  /// @param store a store of the database, which the queries and updates run on
  /// @param models the entity models given to the test API, with their event types
  TestStorage testStorage(Store store, ResolvedModels models, String schemaName, Clock clock);

  /// The provider of the storage of the store's database, by the provider of its module
  static StorageProvider of(Store store) {
    Database database = store.database();
    return ServiceLoader.load(StorageProvider.class).stream()
        .map(Provider::get)
        .filter(provider -> provider.database().equals(database.name()))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("No storage of database " + database.name()));
  }

}
