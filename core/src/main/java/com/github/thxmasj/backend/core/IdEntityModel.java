package com.github.thxmasj.backend.core;

import static com.github.thxmasj.backend.core.TransitionModelBuilder.WithEvent.onEvent;
import static java.util.stream.Collectors.toList;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/// Ids of entities. An entity's ids are the events of the id entity with the same entity id, triggered like any other
/// event. The data of an id event is unique among the events of its type, so an id event is rejected if its id is
/// already taken.
public interface IdEntityModel extends EntityModel {

  Map<State, List<TransitionModel<?, ?>>> transitions();

  static IdEntityModel of(String name, UUID id, EventType<?, ?>... eventTypes) {
    List<TransitionModel<?, ?>> transitions = Arrays.stream(eventTypes)
        .map(IdEntityModel::transition)
        .collect(toList());
    return new IdEntityModel() {
      @Override public String name() {return name;}
      @Override public UUID id() {return id;}
      @Override public Map<State, List<TransitionModel<?, ?>>> transitions() {return Map.of(Begin, transitions);}
      @Override public String toString() {return name;}
    };
  }

  @SuppressWarnings("unchecked")
  private static TransitionModel<?, ?> transition(EventType<?, ?> eventType) {
    return onEvent((EventType<Object, Object>) eventType).toSelf().assembleInput().output(d -> d);
  }

}
