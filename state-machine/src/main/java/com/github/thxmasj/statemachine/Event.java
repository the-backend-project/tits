package com.github.thxmasj.statemachine;

import static java.util.Objects.requireNonNull;

import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.stream.Stream;
import reactor.core.publisher.Mono;

public final class Event {

  private final Integer eventNumber;
  private final EventType type;
  private final ZonedDateTime timestamp;
  private final String messageId;
  private final String clientId;
  private String data;
  private Object unmarshalledData;

  public Event(Integer eventNumber, EventType type, Clock clock, String messageId, String clientId) {
    this(eventNumber, type, LocalDateTime.ofInstant(clock.instant(), clock.getZone()), clock, messageId, clientId, null);
  }

  public <T> Event(Integer eventNumber, EventType type, Clock clock, String messageId, String clientId, T data) {
    this(eventNumber, type, LocalDateTime.ofInstant(clock.instant(), clock.getZone()), clock, messageId, clientId, marshal(type, data));
    this.unmarshalledData = data;
  }

  public <T> Event(Integer eventNumber, EventType type, Clock clock, T data) {
    this(eventNumber, type, LocalDateTime.ofInstant(clock.instant(), clock.getZone()), clock, null, null, marshal(type, data));
    this.unmarshalledData = data;
  }

  public Event(Integer eventNumber, EventType type, Clock clock, String messageId, String clientId, String data) {
    this(eventNumber, type, LocalDateTime.ofInstant(clock.instant(), clock.getZone()), clock, messageId, clientId, data);
  }

  public Event(Integer eventNumber, EventType type, Clock clock) {
    this(eventNumber, type, LocalDateTime.ofInstant(clock.instant(), clock.getZone()), clock, null, null, null);
  }

  public Event(Integer eventNumber, EventType type, LocalDateTime timestamp, Clock clock, String messageId, String clientId, String data) {
    requireNonNull(eventNumber);
    requireNonNull(type);
    this.eventNumber = eventNumber;
    this.type = type;
    this.timestamp = ZonedDateTime.of(timestamp, clock.getZone());
    this.messageId = messageId;
    this.clientId = clientId;
    this.data = data;
  }

  public static class LoadedEvent {

    private final Object unmarshalled;
    private final Mono<Object> loaded;
    private final Event event;
    private final Mono<String> notification;

    public LoadedEvent(
        Object unmarshalled,
        Mono<Object> loaded,
        Event event,
        Mono<String> notification
    ) {
      this.unmarshalled = unmarshalled;
      this.loaded = loaded;
      this.event = event;
      this.notification = notification;
    }

    public Mono<String> getNotification() {
      return notification;
    }

    public <T> T getUnmarshalledData(Class<T> type) {
      if (unmarshalled != null && unmarshalled.getClass() == type)
        return type.cast(unmarshalled);
      else
        return null;
    }

    public String getData() {
      return event.getData();
    }

    public <T> Mono<T> getLoadedData(Class<T> type) {
      return loaded.map(type::cast);
    }

    public ZonedDateTime getTimestamp() {
      return event.getTimestamp();
    }

    public String getMessageId() {
      return event.getMessageId();
    }

    public int eventNumber() {
      return event.getEventNumber();
    }

    public EventType type() {
      return event.getType();
    }

    public String clientId() {
      return event.getClientId();
    }
  }

  public Integer getEventNumber() {
    return eventNumber;
  }

  public String getMessageId() {
    return messageId;
  }

  public String getClientId() {
    return clientId;
  }

  public ZonedDateTime getTimestamp() {
    return timestamp;
  }

  public EventType getType() {
    return type;
  }

  public String getTypeName() {
    return type.name();
  }

  public String getData() {
    return data;
  }

  @Override
  public String toString() {
    return "Event{" +
        "eventNumber=" + eventNumber +
        ", type=" + type +
        ", timestamp=" + timestamp +
        ", data=" + data +
        ", clientId=" + clientId +
        ", messageId=" + messageId +
        '}';
  }

  public <T> T getUnmarshalledData() {
    if (unmarshalledData == null && data != null) {
      unmarshalledData = unmarshal(type, data);
    }
    return (T) unmarshalledData;
  }

  public <T> T getUnmarshalledData(Class<T> dataType) {
    if (type.dataType() == null)
      throw new IllegalArgumentException("Event " + getType() + " does not have data type " + dataType.getName() + " but is null");
    if (type.dataType() != dataType)
      throw new IllegalArgumentException("Event " + getType() + " does not have data type " + dataType.getName() + " but " + type.dataType().getName());
    return getUnmarshalledData();
  }

  public String getMarshalledData() {
    if (data == null && unmarshalledData != null) {
      data = marshal(type, unmarshalledData);
    }
    return data;
  }

  public boolean isIncomingRequest() {
    return getClientId() != null;
  }

  static <EVENT> List<EVENT> join(List<EVENT> events, EVENT tail) {
    return Stream.concat(events.stream(), Stream.of(tail)).toList();
  }

  static <EVENT> List<EVENT> join(EVENT head, List<EVENT> events) {
    return Stream.concat(Stream.of(head), events.stream()).toList();
  }

  static <EVENT> List<EVENT> join(List<EVENT> list1, List<EVENT> list2) {
    return Stream.concat(list1.stream(), list2.stream()).toList();
  }

  private static ObjectMapper objectMapper = new ObjectMapper()
      .registerModule(new JavaTimeModule())
      .configure(DeserializationFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE, false)
      .setSerializationInclusion(Include.NON_NULL);

  private static <T> String marshal(EventType eventType, T data) {
    if (data instanceof String s)
      return s;
    else if (data instanceof Number n)
      return n.toString();
    try {
      return objectMapper.writerFor(eventType.dataType()).writeValueAsString(data);
    } catch (JsonProcessingException e) {
      throw new RuntimeException(e);
    }
  }

  private static <T> T unmarshal(EventType eventType, String data) {
    if (eventType.dataType() == String.class)
      return (T) data;
    if (eventType.dataType() == Integer.class)
      return (T) Integer.valueOf(data);
    if (eventType.dataType() == null)
      return null;
    try {
      return objectMapper.readerFor(eventType.dataType()).readValue(data);
    } catch (JsonProcessingException e) {
      throw new RuntimeException(e);
    }
  }

}
