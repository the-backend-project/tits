package com.github.thxmasj.statemachine;

import static com.github.thxmasj.statemachine.BuiltinEventTypes.Rollback;

import com.github.thxmasj.statemachine.BasicEventType.Rollback.Data;
import java.time.Duration;

public interface State {

  Timeout NEVER_TIMEOUT = new Timeout(Duration.ZERO, null);

  String name();

  record Timeout(Duration duration, InputEvent<?> event) {}

  default Timeout timeout() {
    return NEVER_TIMEOUT;
  }

  default Timeout rollbackAfter(Duration duration) {
    return new Timeout(duration, new InputEvent<>(Rollback, new Data(-1, name() + " timed out")));
  }

  default Timeout rollbackAfter(Duration duration, int numberOfEventsToRollback) {
    return new Timeout(duration, new InputEvent<>(Rollback, new Data(-numberOfEventsToRollback, name() + " timed out")));
  }

}
