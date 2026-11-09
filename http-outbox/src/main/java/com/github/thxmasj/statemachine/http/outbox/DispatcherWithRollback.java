package com.github.thxmasj.statemachine.http.outbox;

import com.github.thxmasj.statemachine.EventType;

/// A [Dispatcher] of requests which can be rolled back, with data of type `Y`.
public sealed interface DispatcherWithRollback<X, I, Y> extends Dispatcher<X, I>
    permits AtMostOnceWithRollbackDispatcher, BatchedAtMostOnceWithRollbackDispatcher {

  /// Rolls the request back with data of type `Y`.
  EventType<Y, Void> rollbackDispatched();

}
