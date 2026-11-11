package com.github.thxmasj.backend.core.database.mssql;

import static java.lang.String.format;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toMap;

import com.github.thxmasj.backend.core.BuiltinEventTypes;
import com.github.thxmasj.backend.core.EntityId;
import com.github.thxmasj.backend.core.EntityModel;
import com.github.thxmasj.backend.core.Event;
import com.github.thxmasj.backend.core.EventType;
import com.github.thxmasj.backend.core.State;
import com.github.thxmasj.backend.core.TransitionModelBuilder.TransitionModel;
import com.github.thxmasj.backend.core.database.MappingFailure;
import com.github.thxmasj.backend.core.database.Row;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Stream;

public class Mappers {

  public static List<EventType<?, ?>> eventTypesFor(TransitionModel<?, ?> transition) {
    ArrayList<EventType<?, ?>> eventTypes = new ArrayList<>();
    eventTypes.add(transition.eventType());
    transition.filters().stream().map(f -> f.alternative().model()).map(Mappers::eventTypesFor).forEach(eventTypes::addAll);
    return Collections.unmodifiableList(eventTypes);
  }

  /// The event types of the transitions of each entity model, including the builtin event types
  public static Map<EntityModel, List<EventType<?, ?>>> eventTypesByEntityModel(
      Map<EntityModel, Map<State, List<TransitionModel<?, ?>>>> transitions
  ) {
    return transitions.entrySet().stream().collect(toMap(Entry::getKey, e -> eventTypes(e.getValue().values())));
  }

  /// The event types of the transitions of all entity models, including the builtin event types
  public static List<EventType<?, ?>> eventTypes(Map<EntityModel, Map<State, List<TransitionModel<?, ?>>>> transitions) {
    return eventTypes(transitions.values().stream().flatMap(t -> t.values().stream()).toList());
  }

  private static List<EventType<?, ?>> eventTypes(Collection<List<TransitionModel<?, ?>>> transitions) {
    return Stream.concat(
            BuiltinEventTypes.ALL.stream(),
            transitions.stream().flatMap(List::stream).flatMap(t -> eventTypesFor(t).stream())
        )
        .distinct()
        .toList();
  }

  public static Function<UUID, EventType<?, ?>> eventTypeMapper(List<EventType<?, ?>> eventTypes) {
    Map<UUID, EventType<?, ?>> idToEventType = eventTypes.stream().collect(toMap(EventType::id, e -> e));
    return idToEventType::get;
  }

  public static BiFunction<EntityId, Row, Event<?>> eventMapper(
      List<EventType<?, ?>> eventTypes,
      Clock clock
  ) {
    var eventTypeMapper = Mappers.eventTypeMapper(eventTypes);
    return (entityId, row) -> {
      UUID eventTypeId = row.get("Type", UUID.class);
      EventType<?, ?> eventType = eventTypeMapper.apply(eventTypeId);
      if (eventType == null)
        throw new MappingFailure(format(
            "No event type for type id %s. Have:\n%s",
            eventTypeId,
            eventTypes.stream().map(t -> t.id() + " -> " + t.name()).collect(joining("\n"))
        ));
      try {
        return new Event<>(
            entityId.value(),
            value(row, "EventNumber", Integer.class),
            eventType,
            value(row, "Timestamp", LocalDateTime.class),
            clock,
            row.get("Data", byte[].class)
        );
      } catch (Exception e) {
        throw new MappingFailure(e);
      }
    };
  }

  private static <T> T value(Row row, String name, Class<T> type) {
        return requireNonNull(row.get(name, type), format("No value for %s", name));
    }

}
