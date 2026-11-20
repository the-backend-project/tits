package com.github.thxmasj.backend.core.internal.database;

import java.util.UUID;
import reactor.core.publisher.Mono;

/// Deletes a delayed trigger
public interface DeleteDelayedTrigger {

  /// Deletes the delayed trigger of the entity with the event number, if any. Completes with the number of triggers
  /// deleted.
  Mono<Integer> execute(UUID entityId, int eventNumber);

}
