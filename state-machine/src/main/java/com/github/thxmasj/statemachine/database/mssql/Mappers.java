package com.github.thxmasj.statemachine.database.mssql;

import static java.lang.String.format;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toMap;

import com.github.thxmasj.statemachine.EntityId;
import com.github.thxmasj.statemachine.EntityModel;
import com.github.thxmasj.statemachine.Event;
import com.github.thxmasj.statemachine.EventType;
import com.github.thxmasj.statemachine.TransitionModelBuilder.TransitionModel;
import com.github.thxmasj.statemachine.database.MappingFailure;
import com.github.thxmasj.statemachine.database.Row;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;

public class Mappers {

  public static List<EventType<?, ?>> eventTypesFor(TransitionModel<?, ?> transition) {
    ArrayList<EventType<?, ?>> eventTypes = new ArrayList<>();
    eventTypes.add(transition.eventType());
    transition.filters().stream().map(f -> f.alternative().model()).map(Mappers::eventTypesFor).forEach(eventTypes::addAll);
    return Collections.unmodifiableList(eventTypes);
  }

  public static Function<UUID, EventType<?, ?>> eventTypeMapper(List<EventType<?, ?>> eventTypes) {
    Map<UUID, EventType<?, ?>> idToEventType = eventTypes.stream().collect(toMap(EventType::id, e -> e));
    return idToEventType::get;
  }

  public static BiFunction<EntityId, Row, Event<?>> eventMapper(
      EntityModel entityModel,
      List<EventType<?, ?>> eventTypes,
      Clock clock
  ) {
    var eventTypeMapper = Mappers.eventTypeMapper(eventTypes);
    return (entityId, row) -> {
      UUID eventTypeId = row.get("Type", UUID.class);
      EventType<?, ?> eventType = eventTypeMapper.apply(eventTypeId);
      if (eventType == null) throw new MappingFailure(format("No event type for type id %s in entity model %s", eventTypeId, entityModel.name()));
      try {
        return new Event<>(
            entityId.value(),
            value(row, "EventNumber", Integer.class),
            eventType,
            value(row, "Timestamp", LocalDateTime.class),
            clock,
            nullableString(row, "Data")
        );
      } catch (Exception e) {
        throw new MappingFailure(e);
      }
    };
  }

  private static <T> T value(Row row, String name, Class<T> type) {
        return requireNonNull(row.get(name, type), format("No value for %s", name));
    }

    private static String nullableString(Row row, String name) {
        return row.get(name, String.class);
    }

}
