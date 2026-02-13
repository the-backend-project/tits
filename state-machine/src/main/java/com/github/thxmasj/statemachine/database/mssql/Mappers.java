package com.github.thxmasj.statemachine.database.mssql;

import static java.lang.String.format;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toMap;

import com.github.thxmasj.statemachine.EntityId;
import com.github.thxmasj.statemachine.EntityModel;
import com.github.thxmasj.statemachine.Event;
import com.github.thxmasj.statemachine.EventType;
import com.github.thxmasj.statemachine.OutboxElement;
import com.github.thxmasj.statemachine.OutboxQueue;
import com.github.thxmasj.statemachine.TransitionModelBuilder.TransitionModel;
import com.github.thxmasj.statemachine.database.MappingFailure;
import com.github.thxmasj.statemachine.database.Row;
import com.github.thxmasj.statemachine.message.http.HttpMessageParser;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

public class Mappers {

  public static List<EventType<?, ?>> eventTypesFor(TransitionModel<?, ?> transition) {
    if (transition.filters().isEmpty())
      return List.of(transition.eventType());
    else {
      ArrayList<EventType<?, ?>> eventTypes = new ArrayList<>(transition.filters().size() + 1);
      eventTypes.add(transition.eventType());
      transition.filters().forEach(f -> eventTypes.add(f.alternative().model().eventType()));
      return Collections.unmodifiableList(eventTypes);
    }
  }

  public static Function<UUID, EventType<?, ?>> eventTypeMapper(List<EventType<?, ?>> eventTypes) {
    Map<UUID, EventType<?, ?>> idToEventType = eventTypes.stream().collect(toMap(EventType::id, e -> e));
    return idToEventType::get;
  }

  public static Function<Row, Event<?>> eventMapper(
        List<EventType<?, ?>> eventTypes,
        Clock clock
    ) {
        var eventTypeMapper = Mappers.eventTypeMapper(eventTypes);
        return (row) -> {
            try {
                return new Event<>(
                        value(row, "EventNumber", Integer.class),
                        eventTypeMapper.apply(row.get("Type", UUID.class)),
                        value(row, "Timestamp", LocalDateTime.class),
                        clock,
                        nullableString(row, "MessageId"),
                        nullableString(row, "ClientId"),
                        nullableString(row, "Data")
                );
            } catch (Exception e) {
                throw new MappingFailure(e);
            }
        };
    }

  static  Function<Row, OutboxElement> queueElementMapper(
      List<EntityModel> entityModels,
      Clock clock,
      OutboxQueue queue,
      LocalDateTime now
  ) {
    return row -> new OutboxElement(
        row.get("ElementId", byte[].class),
        row.get("RequestId", UUID.class),
        new EntityId.UUID(row.get("EntityId", UUID.class)),
        entityModels.stream().filter(e -> e.id().equals(row.get("EntityModelId", UUID.class))).findFirst().orElseThrow(),
        requireNonNull(row.get("EventNumber", Integer.class)),
        requireNonNull(row.get("CreatorId", UUID.class)),
        requireNonNull(queue),
        requireNonNull(row.get("Guaranteed", Boolean.class)),
        ZonedDateTime.of(requireNonNull(row.get("EnqueuedAt", LocalDateTime.class)), clock.getZone()),
        HttpMessageParser.parseRequest(row.get("Data", String.class)),
        row.get("CorrelationId", String.class),
        requireNonNull(row.get("Attempt", Integer.class)),
        ZonedDateTime.of(requireNonNull(row.get("NextAttemptAt", LocalDateTime.class)), clock.getZone()),
        ZonedDateTime.of(now, clock.getZone())
    );
  }

  private static <T> T value(Row row, String name, Class<T> type) {
        return requireNonNull(row.get(name, type), format("No value for %s", name));
    }

    private static String nullableString(Row row, String name) {
        return row.get(name, String.class);
    }

}
