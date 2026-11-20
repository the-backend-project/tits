package com.github.thxmasj.backend.core.internal.database;

import com.github.thxmasj.backend.core.EntityModel;
import com.github.thxmasj.backend.core.EventLog;
import com.github.thxmasj.backend.core.EventType;
import java.util.List;
import java.util.UUID;
import reactor.core.publisher.Mono;

/// The events of the entity with the last index event of a group, i.e. the index entity with the last index event of
/// a type whose data starts with the marshalled group, ordered by data, or of the one before it, and so on
public interface EventsByLastIndexEvent {

  /// The event log of the entity of the entity model with the same entity id as the index entity with the index event
  /// at the position, counted from the last one, among the index events of the type whose data starts with the group,
  /// ordered by their data compared byte by byte as unsigned. Its events are in the order of their event numbers.
  /// Completes empty if the group has no index events. If it has fewer index events than the position, the entity of
  /// the first one is given.
  ///
  /// @param eventTypes the event types of the entity model, which the events are of
  /// @param type the id of the type of the index events
  /// @param group the marshalled group, i.e. the prefix of the data of its index events
  /// @param position the position of the index event from the last one, which is at position 1
  Mono<EventLog> execute(EntityModel entityModel, List<EventType<?, ?>> eventTypes, UUID type, byte[] group, int position);

}
