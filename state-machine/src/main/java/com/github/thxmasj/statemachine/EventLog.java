package com.github.thxmasj.statemachine;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static java.util.Collections.unmodifiableList;

public record EventLog(EntityModel entityModel, EntityId entityId, List<SecondaryId> secondaryIds, List<Event<?>> events) {

  public int lastEventNumber() {
    return events.isEmpty() ? 0 : events.getLast().eventNumber();
  }

  public <T> Event<T> one(EventType<?, T> eventType) {
    return effectiveEvents().stream()
        .filter(e -> e.type().id().equals(eventType.id()))
        .map(e -> (Event<T>)e)
        .findFirst()
        .orElseThrow();
  }

  @SafeVarargs
  public final <T> Event<T> one(EventType<?, T>... eventTypes) {
    return effectiveEvents().stream()
        .filter(e -> Stream.of(eventTypes).map(EventType::id).toList().contains(e.type().id()))
        .map(e -> (Event<T>)e)
        .findFirst()
        .orElseThrow();
  }

  public <T> Event<T> last(EventType<?, T> eventType) {
    return effectiveEvents().reversed().stream()
        .filter(e -> e.type().id().equals(eventType.id()))
        .map(e -> (Event<T>)e)
        .findFirst()
        .orElseThrow();
  }

  public <T> Optional<Event<T>> lastIfExists(EventType<?, T> eventType) {
    return effectiveEvents().reversed().stream()
        .filter(e -> e.type().id().equals(eventType.id()))
        .map(e -> (Event<T>)e)
        .findFirst();
  }

  public <T> List<Event<T>> all(EventType<?, T> eventType) {
    return effectiveEvents().stream()
        .filter(e -> e.type().id().equals(eventType.id()))
        .map(e -> (Event<T>)e)
        .toList();
  }

  /**
   * Effective events, ignoring events that were rolled back.
   */
  public List<Event<?>> effectiveEvents() {
    // Traverse the event log backwards and skip events between a rollback and its target exclusively
    List<Event<?>> effectiveEventsReversed = new ArrayList<>(events.size());
    int skipTo = Integer.MAX_VALUE;
    for (Event<?> event : events.reversed()) {
      if (skipTo >= event.eventNumber()) {
        if (event.type() instanceof BasicEventType.Rollback rollbackType) {
          skipTo = Event.unmarshal(rollbackType, event.data()).toNumber();
        } else {
          effectiveEventsReversed.add(event);
        }
      }
    }
    return unmodifiableList(effectiveEventsReversed.reversed());
  }

}
