package com.github.thxmasj.statemachine;

import static com.github.thxmasj.statemachine.BuiltinEventTypes.Rollback;

import com.github.thxmasj.statemachine.BasicEventType.Rollback.Data;
import java.time.Duration;
import java.util.function.Function;

public interface State {

  Timeout<Void> NEVER_TIMEOUT = new Timeout<>(Duration.ZERO, null, null);

  String name();

  record Timeout<I>(Duration duration, EventType<I, ?> eventType, Function<Integer, I> eventData) {
    public InputEvent<I> event(int lastEventNumber) {
      return new InputEvent<>(eventType, eventData.apply(lastEventNumber));
    }
  }

  default Timeout<?> timeout() {
    return NEVER_TIMEOUT;
  }

  default Timeout<?> rollbackAfter(Duration duration) {
    return new Timeout<>(duration, Rollback, lastEventNumber -> new Data(-1, lastEventNumber, name() + " timed out"));
  }

  default Timeout<?> rollbackAfter(Duration duration, int numberOfEventsToRollback) {
    return new Timeout<>(duration, Rollback, lastEventNumber -> new Data(-numberOfEventsToRollback, lastEventNumber, name() + " timed out"));
  }

}
