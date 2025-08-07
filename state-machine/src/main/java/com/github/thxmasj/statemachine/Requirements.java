package com.github.thxmasj.statemachine;

import com.github.thxmasj.statemachine.Requirements.Requirement.OutgoingRequest;

import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toList;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class Requirements {

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
        .filter(r -> r instanceof Requirement er && dataType.equals(er.dataType()))
        .collect(toList());
  }

  public static  Requirements of(Requirement... reqs) {
    return of(Arrays.asList(reqs));
  }

  private static  Requirements of(List<Requirement> reqs) {
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

  public static Requirement one(EventType... eligibleEventTypes) {
    return new Requirement(Arrays.asList(eligibleEventTypes), Requirement.Type.One);
  }

  public static Requirement all(EventType... eventTypes) {
    return new Requirement(Arrays.asList(eventTypes), Requirement.Type.All);
  }

  public static Requirement last(EventType eventType) {
    return new Requirement(List.of(eventType), Requirement.Type.Last);
  }

  public static Requirement last() {
    return new Requirement(List.of(), Requirement.Type.Last);
  }

  public static Requirement last(Class<?> dataType) {
    return new Requirement(List.of(), Requirement.Type.Last, dataType, null);
  }

  public static Requirement lastIfExists(EventType eventType) {
    return new Requirement(List.of(eventType), Requirement.Type.LastIfExists);
  }

  public static Requirement outgoingRequest(OutboxQueue queue, EventType eventType, Class<?> dataType) {
    return new Requirement(List.of(eventType), Requirement.Type.Last, new OutgoingRequest(queue, dataType));
  }

  public record Requirement(
      List<EventType> eventTypes,
      Type type,
      Class<?> dataType,
      OutgoingRequest outgoingRequest
  ) {

    public enum Type {All, One, Last, LastIfExists}
    public record OutgoingRequest(OutboxQueue queue, Class<?> dataType) {}

    public Requirement(List<EventType> eventTypes, Type type) {
      this(Collections.unmodifiableList(eventTypes), type, null, null);
    }

    public Requirement(List<EventType> eventTypes, Type type, OutgoingRequest outgoingRequest) {
      this(Collections.unmodifiableList(eventTypes), type, null, outgoingRequest);
    }

    @Override
    public String toString() {
      return type().name() + "(" + eventTypes().stream().map(Object::toString).collect(joining(","))+ ")";
    }

    @Override
    public boolean equals(Object o) {
      if (o instanceof Requirement other) {
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
