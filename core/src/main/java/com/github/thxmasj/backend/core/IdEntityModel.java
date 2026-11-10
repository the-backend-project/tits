package com.github.thxmasj.backend.core;

import static com.github.thxmasj.backend.core.TransitionModelBuilder.WithEvent.onEvent;
import static java.util.function.Function.identity;

import com.github.thxmasj.backend.core.TransitionModelBuilder.TransitionModel;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

/// Ids of entities. An entity's ids are the events of the id entity with the same entity id, triggered like any other
/// event. The data of an id event is unique among the events of its type, so an id event is rejected if its id is
/// already taken.
public final class IdEntityModel implements EntityModel {

  public static final IdEntityModel Id = new IdEntityModel();

  private IdEntityModel() {}

  @Override
  public String name() {
    return "Id";
  }

  @Override
  public UUID id() {
    return UUID.fromString("3c8e1f5a-7b2d-4e9c-a6f0-8d4b2e7c1a95");
  }

  @Override
  public String toString() {
    return name();
  }

  /// The transitions of the id entities, accepting the id events triggered by the given transitions
  public Map<State, List<TransitionModel<?, ?>>> transitions(Map<EntityModel, Map<State, List<TransitionModel<?, ?>>>> transitions) {
    List<TransitionModel<?, ?>> idTransitions = transitions.values().stream()
        .flatMap(transitionsByState -> transitionsByState.values().stream())
        .flatMap(List::stream)
        .flatMap(IdEntityModel::idEventTypes)
        .distinct()
        .<TransitionModel<?, ?>>map(IdEntityModel::transition)
        .toList();
    return Map.of(Begin, idTransitions);
  }

  private static Stream<EventType<?, ?>> idEventTypes(TransitionModel<?, ?> transition) {
    return Stream.of(
        transition.triggers().stream()
            .filter(trigger -> trigger.entityModel() == Id)
            .<EventType<?, ?>>map(trigger -> trigger.eventSpec().eventType()),
        transition.filters().stream().flatMap(filter -> idEventTypes(filter.alternative().model())),
        Stream.ofNullable(transition.reverseModel()).flatMap(IdEntityModel::idEventTypes),
        Stream.ofNullable(transition.rejectModel()).flatMap(IdEntityModel::idEventTypes)
    ).flatMap(identity());
  }

  @SuppressWarnings("unchecked")
  private static TransitionModel<?, ?> transition(EventType<?, ?> eventType) {
    return onEvent((EventType<Object, Object>) eventType).toSelf().assembleInput().output(d -> d);
  }

}
