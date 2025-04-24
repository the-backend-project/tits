package com.github.thxmasj.statemachine;

public interface EventType {

  String name();

  int id();

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
