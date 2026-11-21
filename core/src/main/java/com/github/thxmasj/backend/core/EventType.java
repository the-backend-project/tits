package com.github.thxmasj.backend.core;

import java.util.UUID;

public interface EventType<I, O> {

  String name();

  UUID id();

  DataType<I> inputDataType();

  DataType<O> outputDataType();

  /// Whether events of the type are stored. An event which is not stored takes part in its transition like any other:
  /// it is calculated, may trigger events on other entities, which are stored if they are of stored types, is given to
  /// the one who triggered it and is told to the [Listener]. But it is not written to the event log, so the state of
  /// its entity must not depend on it: on an entity which is stored, its transitions stay in their state
  /// (`toSelf()`), which the state machine checks when it is created. An event which is not stored creating an entity,
  /// i.e. from [EntityModel#Begin], makes the entity one of the session only: none of its events are stored, and it
  /// can't be found after the session.
  default boolean stored() {
    return true;
  }

}
