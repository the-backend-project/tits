package com.github.thxmasj.backend.core.internal.database;

import com.github.thxmasj.backend.core.EventLog;
import com.github.thxmasj.backend.core.EventType;
import reactor.core.publisher.Mono;

/// The events of the entity having an id, found by the id event of its id entity
public interface EventsByIdEvent {

  /// The event log of the entity with the same entity id as the id entity whose id event of the type has the id as its
  /// data, with the entity's entity model and its events in the order of their event numbers. Completes empty if no
  /// entity has the id.
  ///
  /// @param id the marshalled id, i.e. the data of the id event
  Mono<EventLog> execute(EventType<?, ?> type, byte[] id);

}
