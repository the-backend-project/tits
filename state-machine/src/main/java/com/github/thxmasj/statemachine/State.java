package com.github.thxmasj.statemachine;

import java.time.Duration;
import java.util.Optional;

public interface State {

  String name();

  default <I, T> Choice<I, T> choice() {
    return null;
  }

  record Timeout(Duration duration, EventType eventType) {}

  default Optional<Timeout> timeout() {
    return Optional.empty();
  }

  default boolean isChoice() {
    return choice() != null;
  }

}
