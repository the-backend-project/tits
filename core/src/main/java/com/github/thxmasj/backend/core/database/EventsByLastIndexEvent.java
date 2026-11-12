package com.github.thxmasj.backend.core.database;

import com.github.thxmasj.backend.core.EntityModel;
import com.github.thxmasj.backend.core.EventLog;
import com.github.thxmasj.backend.core.EventType;
import java.time.Clock;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import reactor.core.publisher.Mono;

/// Events of the entity with the last index event of a group, i.e. the index entity with the last index event of a type
/// whose data starts with the marshalled group, ordered by data, or of the one before it, and so on
public class EventsByLastIndexEvent {

  private final DatabaseClient client;
  private final Clock clock;
  private final String sql;

  /// @param sql the SQL selecting the events of the entity, with its entity id on each row. It selects no rows if the
  ///            group has no index events.
  public EventsByLastIndexEvent(
      DatabaseClient client,
      String sql,
      Clock clock
  ) {
    this.client = client;
    this.clock = clock;
    this.sql = sql;
  }

  /// Completes empty if the group has no index events. If it has fewer index events than the position, the entity of
  /// the first one is given.
  ///
  /// @param group the marshalled group, i.e. the prefix of the data of its index events
  public Mono<EventLog> execute(EntityModel entityModel, List<EventType<?, ?>> eventTypes, UUID type, byte[] group, int position) {
    var eventMapper = Mappers.eventMapper(eventTypes, clock);
    return client.sql(sql)
        .name("EventsByLastIndexEvent")
        .bind("type", type)
        .bind("group", group)
        .bind("groupEnd", end(group))
        .bind("position", position)
        .map(row -> {
          UUID entityId = row.get("EntityId", UUID.class);
          try {
            return eventMapper.apply(entityId, row);
          } catch (MappingFailure e) {
            throw new MappingFailure("EventsByLastIndexEvent: Failed to map row to event for entity model " + entityModel.name(), e);
          }
        })
        .all()
        .collectList()
        .filter(events -> !events.isEmpty())
        .map(events -> new EventLog(
            entityModel,
            events.getFirst().entityId(),
            Collections.unmodifiableList(events)
        ));
  }

  /// The least value greater than all values starting with the prefix
  private static byte[] end(byte[] prefix) {
    byte[] end = Arrays.copyOf(prefix, prefix.length);
    for (int i = end.length - 1; i >= 0; i--) {
      if (end[i] != (byte) 0xFF) {
        end[i]++;
        return Arrays.copyOf(end, i + 1);
      }
    }
    throw new IllegalArgumentException("Prefix has no end, as all its bytes are 0xFF");
  }

}
