package com.github.thxmasj.statemachine;

import java.util.Objects;

import static java.util.Objects.requireNonNull;

public interface EntityId {

  record UUID(java.util.UUID value) implements EntityId {
    public UUID { requireNonNull(value); }

    @Override
    public boolean equals(Object o) {
      if (o == null || getClass() != o.getClass())
        return false;
      UUID uuid = (UUID) o;
      return Objects.equals(value, uuid.value);
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(value);
    }
  }

  java.util.UUID value();

}
