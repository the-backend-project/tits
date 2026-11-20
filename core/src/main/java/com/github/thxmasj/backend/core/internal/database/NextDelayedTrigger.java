package com.github.thxmasj.backend.core.internal.database;

import com.github.thxmasj.backend.core.DelaySpecification;
import reactor.core.publisher.Mono;

/// Takes the next delayed trigger that is due
public interface NextDelayedTrigger {

  /// The delayed trigger whose deadline has passed the earliest, if any, whose deadline is then postponed to its next
  /// attempt, given by the backoff and the number of attempts so far. The trigger is taken atomically, so that two
  /// state machines on the same storage don't take the same attempt. Completes empty if no trigger is due.
  Mono<? extends DelayedTrigger<?>> execute(DelaySpecification backoff);

}
