package com.github.thxmasj.statemachine;

import java.time.Duration;
import java.util.Optional;

public interface State {

  String name();

  record Timeout(Duration duration, EventType eventType) {}

  default Optional<Timeout> timeout() {
    return Optional.empty();
  }

}
