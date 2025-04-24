package com.github.thxmasj.statemachine;

import static java.util.Objects.requireNonNull;

public interface EntityId {

  record UUID(java.util.UUID value) implements EntityId {
    public UUID { requireNonNull(value); }
  }

  java.util.UUID value();

}
