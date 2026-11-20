package com.github.thxmasj.backend.core.internal.database;

import reactor.core.publisher.Flux;

/// The conditional triggers whose conditions are met
public interface ConditionalTriggers {

  /// The oldest waiting conditional triggers whose condition is met, at most the given limit, in the order of their
  /// timestamps. The condition of a trigger is met when at least one entity is indexed by its key with its reference
  /// type, and each entity indexed so has a last event of one of its last types.
  Flux<ConditionalTrigger<?>> execute(int limit);

}
