package com.github.thxmasj.statemachine;

import java.util.UUID;

public interface EventType {

  String name();

  UUID id();

  default Class<?> dataType() {
    return null;
  }

  default boolean isRollback() {
    return false;
  }

  default boolean isCancel() {
    return false;
  }

  default boolean isReversible() {
    return true;
  }

  default boolean isReadOnly() {
    return false;
  }

}
