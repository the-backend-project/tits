package com.github.thxmasj.statemachine;

import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toList;

import com.github.thxmasj.statemachine.OutboxWorker.ExchangeType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class Requirements {

  public enum Type {
    All,
    One,
    Last,
    LastIfExists
  }

  private final List<Requirement> requirements;

  private Requirements(List<Requirement> requirements) {
    this.requirements = requirements;
  }

  public List<Requirement> asList() {
    return Collections.unmodifiableList(requirements);
  }

  public List<Requirement> on(EventType type) {
    return requirements.stream()
        .filter(r -> r.eventTypes().contains(type))
        .collect(toList());
  }

  public final List<Requirement> on(EventType... types) {
    return requirements.stream()
        .filter(r -> r.eventTypes().equals(List.of(types)))
        .collect(toList());
  }

  public final List<Requirement> on(Class<?> dataType) {
    return requirements.stream()
        .filter(r -> r instanceof EventRequirement er && dataType.equals(er.dataType()))
        .collect(toList());
  }

  public static  Requirements of(Requirement... reqs) {
    return of(Arrays.asList(reqs));
  }

  private static  Requirements of(List<Requirement> reqs) {
//    Set tmp = new HashSet<>();
//    for (var requirement : reqs) {
//      if (!(requirement instanceof EntityRequirement || requirement instanceof EntityIfExistsRequirement)
//          && !tmp.addAll(requirement.eventTypes())) {
//        throw new IllegalArgumentException("Only one requirement per event type allowed");
//      }
//    }
    return new Requirements(reqs);
  }

  public final Requirements and(Requirement... newRequirements) {
    ArrayList<Requirement> reqs = new ArrayList<>(requirements);
    reqs.addAll(Arrays.asList(newRequirements));
    return of(reqs);
  }

  public static  Requirements none() {
    return new Requirements(List.of());
  }

  public static  EventRequirement one(EventType... eligibleEventTypes) {
    return new EventRequirement(Arrays.asList(eligibleEventTypes), Type.One);
  }

  public static  EventRequirement one(
      NotificationRequirement notification,
      EventType... eligibleEventTypes
  ) {
    return new EventRequirement(Arrays.asList(eligibleEventTypes), Type.One, notification);
  }

  public static  EventRequirement all(EventType... eventTypes) {
    return new EventRequirement(Arrays.asList(eventTypes), Type.All);
  }

  public static  EventRequirement last(EventType eventType) {
    return new EventRequirement(List.of(eventType), Type.Last);
  }

  public static  EventRequirement last() {
    return new EventRequirement(List.of(), Type.Last);
  }

  public static  EventRequirement last(Class<?> dataType) {
    return new EventRequirement(List.of(), Type.Last, dataType, null);
  }

  public static  EventRequirement last(
      NotificationRequirement notification,
      EventType eventType
  ) {
    return new EventRequirement(List.of(eventType), Type.Last, notification);
  }

  public static  EventRequirement lastIfExists(EventType eventType) {
    return new EventRequirement(List.of(eventType), Type.LastIfExists);
  }

  public static  EventRequirement lastIfExists(
      NotificationRequirement notification,
      EventType eventType
  ) {
    return new EventRequirement(List.of(eventType), Type.LastIfExists, notification);
  }

  public static  EventRequirement incomingRequest(EventType eventType, Class<?> dataType) {
    return last(notifications(null, ExchangeType.IncomingRequest, dataType), eventType);
  }

  public static  EventRequirement outgoingRequest(OutboxQueue queue, EventType eventType, Class<?> dataType) {
    return last(notifications(queue, ExchangeType.OutgoingRequest, dataType), eventType);
  }

  private static <T> NotificationRequirement notifications(OutboxQueue queue, ExchangeType exchangeType, Class<T> dataType) {
    return new NotificationRequirement(queue, exchangeType, dataType);
  }

  public interface Requirement {

    Type type();

    List<EventType> eventTypes();

  }

  public record NotificationRequirement(OutboxQueue queue, ExchangeType exchangeType, Class<?> dataType) {}

  public record EventRequirement(
      List<EventType> eventTypes,
      Type type,
      Class<?> dataType,
      NotificationRequirement notification
  ) implements Requirement {

    public EventRequirement(List<EventType> eventTypes, Type type) {
      this(Collections.unmodifiableList(eventTypes), type, null, null);
    }

    public EventRequirement(List<EventType> eventTypes, Type type, NotificationRequirement notification) {
      this(Collections.unmodifiableList(eventTypes), type, null, notification);
    }

    @Override
    public String toString() {
      return type().name() + "(" + eventTypes().stream().map(Object::toString).collect(joining(","))+ ")";
    }

    @Override
    public boolean equals(Object o) {
      if (o instanceof EventRequirement other) {
        if (!eventTypes().equals(other.eventTypes()))
          return false;
        return type().equals(other.type());
      }
      return false;
    }

    @Override
    public int hashCode() {
      return eventTypes().hashCode() + type().hashCode();
    }

  }

  public static class MissingRequirement extends RuntimeException {

    public MissingRequirement(String message) {
      super(message);
    }
  }

}
