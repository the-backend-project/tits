package com.github.thxmasj.backend.core.internal.database;

import com.github.thxmasj.backend.core.EntityModel;
import com.github.thxmasj.backend.core.State;
import com.github.thxmasj.backend.core.TransitionModel;
import com.github.thxmasj.backend.core.database.Database;
import com.github.thxmasj.backend.core.database.DatabaseClient;
import java.time.Clock;
import java.util.List;
import java.util.Map;

/// The storage of a [Database] of the library, which implements this along with [Database]
public interface StorageProvider {

  /// The queries and updates of the schema
  ///
  /// @param client a client of this database
  /// @param transitions the transitions of the state machine, which give the entity models and event types
  Storage storage(
      DatabaseClient client,
      Map<EntityModel, Map<State, List<TransitionModel<?, ?>>>> transitions,
      String schemaName,
      Clock clock
  );

  /// The storage of the database, which must be one of the library's
  static Storage storage(
      Database database,
      DatabaseClient client,
      Map<EntityModel, Map<State, List<TransitionModel<?, ?>>>> transitions,
      String schemaName,
      Clock clock
  ) {
    if (!(database instanceof StorageProvider provider))
      throw new IllegalArgumentException("Database " + database.name() + " is not one of the library's databases");
    return provider.storage(client, transitions, schemaName, clock);
  }
}
