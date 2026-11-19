package com.github.thxmasj.backend.core.internal.database;

import com.github.thxmasj.backend.core.EntityModel;
import com.github.thxmasj.backend.core.State;
import com.github.thxmasj.backend.core.TransitionModel;
import com.github.thxmasj.backend.core.database.Database;
import com.github.thxmasj.backend.core.database.DatabaseClient;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.ServiceLoader.Provider;

/// The storage of a [Database]. The module of a database provides its storage as a service of its own, found on the
/// module path or class path like the [Database], so that the public [Database] has no internal types.
public interface StorageProvider {

  /// The name of the database this is the storage of, see [Database#name()]
  String database();

  /// The queries and updates of the schema
  ///
  /// @param client a client of the database
  /// @param transitions the transitions of the state machine, which give the entity models and event types
  Storage storage(
      DatabaseClient client,
      Map<EntityModel, Map<State, List<TransitionModel<?, ?>>>> transitions,
      String schemaName,
      Clock clock
  );

  /// The storage of the database, by the provider of its module
  static Storage storage(
      Database database,
      DatabaseClient client,
      Map<EntityModel, Map<State, List<TransitionModel<?, ?>>>> transitions,
      String schemaName,
      Clock clock
  ) {
    return ServiceLoader.load(StorageProvider.class).stream()
        .map(Provider::get)
        .filter(provider -> provider.database().equals(database.name()))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("No storage of database " + database.name()))
        .storage(client, transitions, schemaName, clock);
  }
}
