package com.github.thxmasj.backend.core.internal.database;

import com.github.thxmasj.backend.core.EntityModel;
import com.github.thxmasj.backend.core.EventType;
import java.time.LocalDateTime;
import java.util.UUID;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/// The queries and updates of the test API that the [Storage] of the state machine has no counterpart of. Some of them
/// bypass the transitions, and are meant for putting entities in a specific state before a test.
public interface TestStorage {

  /// The entity model the entity was created with. Completes empty if there is no such entity, also for id and index
  /// entities, which have no entity model of their own stored.
  ///
  /// @throws IllegalStateException (as the error of the result) if the entity model is not among those given
  Mono<EntityModel> entityModel(UUID entityId);

  /// The data of the id event of the type with the greatest event number of the id entity, i.e. its marshalled id.
  /// Completes empty if the entity has no such id event.
  Mono<byte[]> lastId(UUID entityId, EventType<?, ?> idType);

  /// The number the next event of the entity of the entity model gets, i.e. 1 if it has no events
  Mono<Integer> nextEventNumber(EntityModel entityModel, UUID entityId);

  /// A delayed trigger an entity is waiting for
  ///
  /// @param deadline the deadline in the time zone of the clock
  /// @param attempt the number of times the event has been attempted
  record PendingDelayedTrigger(
      EntityModel entityModel,
      int eventNumber,
      EventType<?, ?> type,
      LocalDateTime deadline,
      int attempt
  ) {}

  /// The delayed trigger the entity is waiting for, i.e. the one whose event number is that of the next event of the
  /// entity. Completes empty if there is none.
  Mono<PendingDelayedTrigger> delayedTrigger(UUID entityId);

  /// The conditional triggers of the entity, whether their conditions are met or not, in the order of their timestamps
  /// and ids
  Flux<ConditionalTrigger<?>> conditionalTriggers(UUID entityId);

  /// Makes the delayed trigger the entity is waiting for due now. Completes with whether there was one.
  Mono<Boolean> expireDelayedTrigger(UUID entityId);

  /// Deletes the delayed triggers of all entities. Completes with the number deleted.
  Mono<Integer> deleteDelayedTriggers();

}
