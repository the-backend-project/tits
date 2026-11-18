package com.github.thxmasj.backend.core.internal.database;

import static java.lang.String.format;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toMap;

import com.github.thxmasj.backend.core.Event;
import com.github.thxmasj.backend.core.EventType;
import com.github.thxmasj.backend.core.database.MappingFailure;
import com.github.thxmasj.backend.core.database.Row;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;

public class Mappers {

  public static Function<UUID, EventType<?, ?>> eventTypeMapper(List<EventType<?, ?>> eventTypes) {
    Map<UUID, EventType<?, ?>> idToEventType = eventTypes.stream().collect(toMap(EventType::id, e -> e));
    return idToEventType::get;
  }

  public static BiFunction<UUID, Row, Event<?>> eventMapper(
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
            entityId,
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
