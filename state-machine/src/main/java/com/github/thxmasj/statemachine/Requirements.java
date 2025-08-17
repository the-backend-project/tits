package com.github.thxmasj.statemachine;

import static java.util.stream.Collectors.joining;

import com.github.thxmasj.statemachine.Requirements.Requirement.OutgoingRequest;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class Requirements {

  private final List<Requirement<?>> requirements;

  private Requirements(List<Requirement<?>> requirements) {
    this.requirements = requirements;
  }

  public <T> List<Requirement<T>> on(EventType<?, T> type) {
    return requirements.stream()
        .filter(r -> r.eventTypes().contains(type))
        .map(r -> (Requirement<T>)r)
        .toList();
  }

  public final <T> List<Requirement<T>> on(EventType<?, T>... types) {
    return requirements.stream()
        .filter(r -> r.eventTypes().equals(List.of(types)))
        .map(r -> (Requirement<T>)r)
        .toList();
  }

  public final <T> List<Requirement<T>> on(Class<T> dataType) {
    return requirements.stream()
        .filter(r -> dataType.equals(r.dataType()))
        .map(r -> (Requirement<T>)r)
        .toList();
  }

  public static Requirements of(Requirement<?>... reqs) {
    return of(Arrays.asList(reqs));
  }

  private static Requirements of(List<Requirement<?>> reqs) {
    return new Requirements(reqs);
  }

  public static  Requirements none() {
    return new Requirements(List.of());
  }

  public static <T> Requirement<T> outgoingRequest(OutboxQueue queue, EventType<?, T> eventType, Class<T> dataType) {
    return new Requirement<>(List.of(eventType), Requirement.Type.Last, new OutgoingRequest(queue, dataType));
  }

  public record Requirement<T>(
      List<EventType<?, T>> eventTypes,
      Type type,
      Class<T> dataType,
      OutgoingRequest outgoingRequest
  ) {

    public enum Type {All, One, Last, LastIfExists}
    public record OutgoingRequest(OutboxQueue queue, Class<?> dataType) {}

    public Requirement(List<EventType<?, T>> eventTypes, Type type, OutgoingRequest outgoingRequest) {
      this(Collections.unmodifiableList(eventTypes), type, null, outgoingRequest);
    }

    @Override
    public String toString() {
      return type().name() + "(" + eventTypes().stream().map(Object::toString).collect(joining(","))+ ")";
    }

    @Override
    public boolean equals(Object o) {
      if (o instanceof Requirement<?> other) {
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
