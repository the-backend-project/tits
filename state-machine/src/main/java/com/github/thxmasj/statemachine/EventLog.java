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
    if (!events.isEmpty() && events.getLast().type().isCancel()) {
      return List.of();
    }
    // Traverse the event log backwards and skip events between a rollback and client request
    // non-greedily and inclusively
    List<Event<?>> effectiveEvents = new ArrayList<>(events);
    boolean skip = false;
    for (int i = effectiveEvents.size() - 1; i >= 0; i--) {
      if (effectiveEvents.get(i).type().isRollback()) {
        try {
          if (effectiveEvents.get(i).data() == null || Integer.parseInt(effectiveEvents.get(i).data()) > 0) {
            // Rollback arrived after incoming request => skip events backwards until incoming request
            skip = true;
          }
        } catch (NumberFormatException e) {
          throw new IllegalStateException("Rollback event without integer value: " + effectiveEvents.get(i).type() +
              "=" + (effectiveEvents.get(i).data() != null ? "<" + effectiveEvents.get(i).data() + ">" : "N/A"));
        }
        // Always remove rollback event itself
        effectiveEvents.remove(i);
      } else if (effectiveEvents.get(i).isIncomingRequest() && skip) {
        // Last event to skip
        effectiveEvents.remove(i);
        skip = false;
      } else if (skip) {
        effectiveEvents.remove(i);
      }
    }
    return unmodifiableList(effectiveEvents);
  }
}
