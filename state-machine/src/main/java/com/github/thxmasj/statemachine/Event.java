package com.github.thxmasj.statemachine;

import static java.util.Objects.requireNonNull;

import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.github.thxmasj.statemachine.message.http.HttpMessageParser;
import com.github.thxmasj.statemachine.message.http.HttpRequestMessage;
import com.github.thxmasj.statemachine.message.http.HttpResponseMessage;
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
  private String data;
  private T unmarshalledData;

  public Event(UUID entityId, Integer eventNumber, EventType<?, T> type, Clock clock) {
    this(entityId, eventNumber, type, LocalDateTime.ofInstant(clock.instant(), clock.getZone()), clock, null);
  }

  public Event(UUID entityId, Integer eventNumber, EventType<?, T> type, Clock clock, T data) {
    this(entityId, eventNumber, type, LocalDateTime.ofInstant(clock.instant(), clock.getZone()), clock, marshal(data));
    this.unmarshalledData = data;
  }

  public Event(UUID entityId, Integer eventNumber, EventType<?, T> type, Clock clock, String data) {
    this(entityId, eventNumber, type, LocalDateTime.ofInstant(clock.instant(), clock.getZone()), clock, data);
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
    this.data = marshal(data);
    this.unmarshalledData = data;
  }

  public Event(UUID entityId, Integer eventNumber, EventType<?, T> type, LocalDateTime timestamp, Clock clock, String data) {
    this.entityId = entityId;
//    if (type.outputDataType().value() != Void.class)
//      requireNonNull(data, "Event type <" + type.name() + "> requires data of type <" + type.outputDataType().value().getName() + ">");
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

  public String data() {
    return data;
  }

  @Override
  public String toString() {
    return "Event{" +
        "entityId=" + entityId +
        ", eventNumber=" + eventNumber +
        ", type=" + type +
        ", timestamp=" + timestamp +
        ", data=" + data +
        '}';
  }

  public T getUnmarshalledData() {
    if (unmarshalledData == null && data != null) {
      unmarshalledData = unmarshal(type, data);
    }
    return unmarshalledData;
  }

  public String getMarshalledData() {
    if (data == null && unmarshalledData != null) {
      data = marshal(unmarshalledData);
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

  private static final ObjectMapper objectMapper = new ObjectMapper()
      .registerModule(new JavaTimeModule())
      .configure(DeserializationFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE, false)
      .setSerializationInclusion(Include.NON_NULL);

  private static <T> String marshal(T data) {
    return switch (data) {
      case String s -> s;
      case Number n -> n.toString();
      case HttpRequestMessage m -> m.message();
      case HttpResponseMessage m -> m.message();
      case null -> null;
      default -> {
        try {
          yield objectMapper.writeValueAsString(data);
        } catch (JsonProcessingException e) {
          throw new RuntimeException(e);
        }
      }
    };
  }

  public static <T> T unmarshal(EventType<?, T> eventType, String data) {
    if (eventType.outputDataType().value() == String.class)
      return (T) data;
    if (eventType.outputDataType().value() == Integer.class)
      return (T) Integer.valueOf(data);
    if (eventType.outputDataType().value() == HttpRequestMessage.class)
      return (T) HttpMessageParser.parseRequest(data);
    if (eventType.outputDataType().value() == HttpResponseMessage.class)
      return (T) HttpMessageParser.parseResponse(data);
    try {
//      return objectMapper.readerFor(eventType.outputDataType().value()).readValue(data);
      var dataType = eventType.outputDataType();
      return (dataType.value() != null ? objectMapper.readerFor(dataType.value()) : objectMapper.readerFor(dataType.typeReference())).readValue(data);
    } catch (Exception e) {
      throw new RuntimeException("Failed to unmarshal JSON event data for " + eventType.name() + ": " + e.getMessage(), e);
    }
  }

}
