package com.github.thxmasj.backend.core.internal.database;

import com.github.thxmasj.backend.core.DataType;
import com.github.thxmasj.backend.core.EventLog;
import com.github.thxmasj.backend.core.EventType;
import java.util.UUID;
import reactor.core.publisher.Flux;

/// The entities indexed by a key, i.e. those that have an event of a reference type with the key as its data
public interface EntitiesByIndex {

  /// The event logs of the entities indexed by the key, each with its entity model and its events in the order of
  /// their event numbers, in the order of their entity ids. Entities of entity models unknown to the state machine are
  /// left out.
  ///
  /// @param key the marshalled key, i.e. the data of the events of the reference type
  Flux<EventLog> execute(byte[] key, EventType<?, ?> referenceType);

  /// The event logs of the entities indexed by a UUID key, see [#execute(byte\[\], EventType)]
  default Flux<EventLog> execute(UUID key, EventType<?, ?> referenceType) {
    return execute(DataType.uuid().marshal(key), referenceType);
  }

}
