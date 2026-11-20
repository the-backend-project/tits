package com.github.thxmasj.backend.core;

import static java.util.Objects.requireNonNull;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

public final class Event<T> {

  private final UUID entityId;
  private final Integer eventNumber;
  private final EventType<?, T> type;
  private final ZonedDateTime timestamp;
  private byte[] data;
  private T unmarshalledData;

  public Event(UUID entityId, Integer eventNumber, EventType<?, T> type, Clock clock) {
    this(entityId, eventNumber, type, LocalDateTime.ofInstant(clock.instant(), clock.getZone()), clock, null);
  }

  public Event(UUID entityId, Integer eventNumber, EventType<?, T> type, Clock clock, T data) {
    this(entityId, eventNumber, type, LocalDateTime.ofInstant(clock.instant(), clock.getZone()), clock, marshal(type, data));
    this.unmarshalledData = data;
  }

  public Event(UUID entityId, int eventNumber, EventType<?, T> type, ZonedDateTime timestamp) {
    requireNonNull(type, "type is null");
    this.entityId = entityId;
    this.eventNumber = eventNumber;
    this.type = type;
    this.timestamp = timestamp;
  }

  public Event(UUID entityId, int eventNumber, EventType<?, T> type, ZonedDateTime timestamp, T data) {
    requireNonNull(type, "type is null");
    this.entityId = entityId;
    this.eventNumber = eventNumber;
    this.type = type;
    this.timestamp = timestamp;
    this.data = type.outputDataType().marshal(data);
    this.unmarshalledData = data;
  }

  public Event(UUID entityId, Integer eventNumber, EventType<?, T> type, LocalDateTime timestamp, Clock clock, byte[] data) {
    this.entityId = entityId;
    requireNonNull(eventNumber, "eventNumber is null");
    requireNonNull(type, "type is null");
    this.eventNumber = eventNumber;
    this.type = type;
    this.timestamp = ZonedDateTime.of(timestamp, clock.getZone());
    this.data = data;
  }

  public UUID entityId() {
    return entityId;
  }

  public Integer eventNumber() {
    return eventNumber;
  }

  public ZonedDateTime timestamp() {
    return timestamp;
  }

  public EventType<?, T> type() {
    return type;
  }

  public String typeName() {
    return type.name();
  }

  public byte[] data() {
    return data;
  }

  @Override
  public String toString() {
    return "Event{" +
        "entityId=" + entityId +
        ", eventNumber=" + eventNumber +
        ", type=" + type +
        ", typeInput=" + type.inputDataType().name() +
        ", typeOutput=" + type.outputDataType().name() +
        ", timestamp=" + timestamp +
        ", data#=" + (data != null ? data.length : 0) +
        '}';
  }

  public T getUnmarshalledData() {
    if (unmarshalledData == null && data != null) {
      unmarshalledData = unmarshal(type, data);
    }
    return unmarshalledData;
  }

  public byte[] getMarshalledData() {
    if (data == null && unmarshalledData != null) {
      data = type.outputDataType().marshal(unmarshalledData);
    }
    return data;
  }

  static <EVENT> List<EVENT> join(List<EVENT> events, EVENT tail) {
    return Stream.concat(events.stream(), Stream.of(tail)).toList();
  }

  static <EVENT> List<EVENT> join(EVENT head, List<EVENT> events) {
    return Stream.concat(Stream.of(head), events.stream()).toList();
  }

  static List<Event<?>> join(List<Event<?>> list1, List<Event<?>> list2) {
    return Stream.concat(list1.stream(), list2.stream()).toList();
  }

  public static <T> byte[] marshal(EventType<?, T> eventType, T data) {
    return eventType.outputDataType().marshal(data);
  }

  public static <T> T unmarshal(EventType<?, T> eventType, byte[] data) {
    return eventType.outputDataType().unmarshal(data);
  }

}
