package com.github.thxmasj.statemachine;

import java.util.UUID;

public interface OutboxQueue {

  String name();

  UUID id();

  record Impl(String name, UUID id) implements OutboxQueue {}

  static OutboxQueue of(String name, UUID id) {
    return new Impl(name, id);
  }

}
