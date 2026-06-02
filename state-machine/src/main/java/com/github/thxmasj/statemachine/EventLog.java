package com.github.thxmasj.statemachine;

import static com.github.thxmasj.statemachine.Event.join;
import static java.util.Collections.unmodifiableList;
import static java.util.stream.Collectors.joining;

import com.github.thxmasj.statemachine.database.mssql.SchemaNames.SecondaryIdModel;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.stream.Stream;

public record EventLog(
    EntityModel entityModel,
    EntityId entityId,
    List<SecondaryId<?>> secondaryIds,
    List<Event<?>> events
) {

  public int lastEventNumber() {
    return events.isEmpty() ? 0 : events.getLast().eventNumber();
  }

  public <T> T one(Class<T> dataType) {
    return effectiveEvents().stream()
        .filter(e -> dataType.equals(e.type().outputDataType().value()))
        .map(e -> (T)e.getUnmarshalledData())
        .filter(e -> e != null)
        .findFirst()
        .orElseThrow(() -> new NoSuchElementException("one(" + dataType.getSimpleName() + "). Have:\n" + effectiveEvents().stream().map(e -> e.toString()).collect(joining("\n"))));
  }

  private <T> T cast(Class<T> dataType, Object o) {
    try {
      return dataType.cast(o);
    } catch (ClassCastException e) {
      System.out.println("cannot cast to type " + dataType.getSimpleName());
      throw e;
    }
  }

  public <T> T last(Class<T> dataType) {
    System.out.println("Trying to find data from last event having data of type " + dataType.getSimpleName());
    T result = effectiveEvents().reversed().stream()
        .filter(e -> dataType.equals(e.type().outputDataType().value()))
        .map(e -> cast(dataType, e.getUnmarshalledData()))
        .findFirst()
        .orElseThrow(() -> new NoSuchElementException("last(" + dataType.getSimpleName() + ")"));
    System.out.println("And we found it: " + result);
    return result;
  }

  public <T> T one(EventType<?, T> eventType) {
    return effectiveEvents().stream()
        .filter(e -> e.type().id().equals(eventType.id()))
        .map(e -> ((Event<T>)e).getUnmarshalledData())
        .findFirst()
        .orElseThrow(() -> new NoSuchElementException(String.format("one(%s) [log: %s] [effective log: %s]",
            eventType.name(),
            events.stream().map(Event::typeName).collect(joining(",")),
            effectiveEvents().stream().map(Event::typeName).collect(joining(","))
        )));
  }

  @SafeVarargs
  public final <T> T one(EventType<?, T>... eventTypes) {
    return effectiveEvents().stream()
        .filter(e -> Stream.of(eventTypes).map(EventType::id).toList().contains(e.type().id()))
        .map(e -> ((Event<T>)e).getUnmarshalledData())
        .findFirst()
        .orElseThrow();
  }

  public <T> Optional<T> oneIfExists(EventType<?, T> eventType) {
    return effectiveEvents().stream()
        .filter(e -> Stream.of(eventType).map(EventType::id).toList().contains(e.type().id()))
        .map(e -> ((Event<T>)e).getUnmarshalledData())
        .findFirst();
  }

  public <T> T last(EventType<?, T> eventType) {
    return effectiveEvents().reversed().stream()
        .filter(e -> e.type().id().equals(eventType.id()))
        .map(e -> ((Event<T>)e).getUnmarshalledData())
        .findFirst()
        .orElseThrow(() -> new NoSuchElementException("last(" + eventType.name() + ")"));
  }

  public <T> Optional<T> lastIfExists(EventType<?, T> eventType) {
    return effectiveEvents().reversed().stream()
        .filter(e -> e.type().id().equals(eventType.id()))
        .map(e -> ((Event<T>)e).getUnmarshalledData())
        .findFirst();
  }

  public <T> List<T> all(EventType<?, T> eventType) {
    return effectiveEvents().stream()
        .filter(e -> e.type().id().equals(eventType.id()))
        .map(e -> ((Event<T>)e).getUnmarshalledData())
        .toList();
  }

  public long sum(EventType<?, Long> eventType) {
    return all(eventType).stream().mapToLong(l -> l).sum();
  }

  public long count(EventType<?, ?> eventType) {
    return all(eventType).size();
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
          int toNumber = Event.unmarshal(rollbackType, event.data()).toNumber();
          skipTo = toNumber >= 0 ? toNumber : event.eventNumber() - 1 + toNumber ;
        } else {
          effectiveEventsReversed.add(event);
        }
      }
    }
    return unmodifiableList(effectiveEventsReversed.reversed());
  }

  public <T> T id(SecondaryIdModel<T> model) {
    return secondaryIds().stream()
        .filter(id -> id.model() == model)
        .map(id -> (T)id.data())
        .findFirst()
        .orElseThrow();
  }

  public EventLog withNewEvent(Event<?> newEvent) {
    return new EventLog(
        entityModel(),
        entityId(),
        secondaryIds(),
        join(events(), newEvent)
    );
  }

  public static EventLog empty(EntityId id, EntityModel model) {
    return new EventLog(model, id, List.of(), List.of());
  }

}
