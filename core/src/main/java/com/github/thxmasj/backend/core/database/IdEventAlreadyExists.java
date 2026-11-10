package com.github.thxmasj.backend.core.database;

import com.github.thxmasj.backend.core.Event;

/// An id event can't be stored, as an id event of the same type with the same data exists. Not an
/// EventAlreadyExists, as storing the same changes again would fail again.
public class IdEventAlreadyExists extends RuntimeException {

  private final Event<?> idEvent;

  public IdEventAlreadyExists(Event<?> idEvent, String duplicateKey) {
    super("Id event " + idEvent.typeName() + " with data " + duplicateKey + " already exists");
    this.idEvent = idEvent;
  }

  /// The id event that was to be stored
  public Event<?> idEvent() {
    return idEvent;
  }

}
