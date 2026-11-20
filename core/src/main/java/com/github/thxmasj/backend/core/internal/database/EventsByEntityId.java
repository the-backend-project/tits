package com.github.thxmasj.backend.core.internal.database;

import com.github.thxmasj.backend.core.EntityModel;
import com.github.thxmasj.backend.core.EventLog;
import com.github.thxmasj.backend.core.EventType;
import com.github.thxmasj.backend.core.database.UnknownEntity;
import java.util.List;
import java.util.UUID;
import reactor.core.publisher.Mono;

/// The events of an entity
public interface EventsByEntityId {

  /// The event log of the entity of the entity model, with its events in the order of their event numbers. The events
  /// of an id or index entity are its id or index events.
  ///
  /// @param eventTypes the event types of the entity model, which the events are of
  /// @throws UnknownEntity (as the error of the result) if the entity has no events
  Mono<EventLog> execute(EntityModel entityModel, List<EventType<?, ?>> eventTypes, UUID entityId);

}
