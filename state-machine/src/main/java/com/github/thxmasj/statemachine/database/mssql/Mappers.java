package com.github.thxmasj.statemachine.database.mssql;

import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

import com.github.thxmasj.statemachine.EntityId;
import com.github.thxmasj.statemachine.EntityModel;
import com.github.thxmasj.statemachine.Event;
import com.github.thxmasj.statemachine.OutboxElement;
import com.github.thxmasj.statemachine.Subscriber;
import com.github.thxmasj.statemachine.database.MappingFailure;
import com.github.thxmasj.statemachine.database.Row;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.util.UUID;
import java.util.function.Function;

public class Mappers {

    static  Function<Row, Event> eventMapper(
        EntityModel entityModel,
        Clock clock
    ) {
        return (row) -> {
            try {
                return new Event(
                        value(row, "EventNumber", Integer.class),
                        entityModel.eventType(row.get("Type", Integer.class)),
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
      EntityModel entityModel,
      Clock clock,
      Subscriber subscriber,
      LocalDateTime now
  ) {
    return row -> new OutboxElement(
        row.get("ElementId", byte[].class),
        row.get("OutboxElementId", Long.class),
        new EntityId.UUID(row.get("EntityId", UUID.class)),
        entityModel,
        requireNonNull(row.get("EventNumber", Integer.class)),
        requireNonNull(row.get("CreatorId", UUID.class)),
        requireNonNull(subscriber),
        requireNonNull(row.get("Guaranteed", Boolean.class)),
        ZonedDateTime.of(requireNonNull(row.get("EnqueuedAt", LocalDateTime.class)), clock.getZone()),
        row.get("Data", String.class),
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
