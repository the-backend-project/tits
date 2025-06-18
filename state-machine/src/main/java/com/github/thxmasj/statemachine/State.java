package com.github.thxmasj.statemachine;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

public interface State {

  String name();

  default Choice<?> choice() {
    return null;
  }

  record Timeout(Duration duration, EventType eventType) {}

  default Optional<Timeout> timeout() {
    return Optional.empty();
  }

  default List<Class<? extends Action<?>>> actions() {
    return List.of();
  }

  default boolean isChoice() {
    return choice() != null;
  }

}
