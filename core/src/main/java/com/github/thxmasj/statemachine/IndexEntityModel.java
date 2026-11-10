package com.github.thxmasj.statemachine;

import static com.github.thxmasj.statemachine.TransitionModelBuilder.WithEvent.onEvent;
import static java.util.stream.Collectors.toList;

import com.github.thxmasj.statemachine.TransitionModelBuilder.TransitionModel;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface IndexEntityModel extends EntityModel {

  Map<State, List<TransitionModel<?, ?>>> transitions();

  @SafeVarargs
  static IndexEntityModel of(String name, UUID id, EventType<UUID, UUID>... eventTypes) {
    List<TransitionModel<?, ?>> transitions = Arrays.stream(eventTypes)
        .map(t -> onEvent(t).toSelf().assembleInput().output(d -> d))
        .collect(toList());
    return new IndexEntityModel() {
      @Override public String name() {return name;}
      @Override public UUID id() {return id;}
      @Override public Map<State, List<TransitionModel<?, ?>>> transitions() {return Map.of(Begin, transitions);}
    };
  }

  static EventType<UUID, UUID> referenceEventType(String name, UUID id) {
    return BasicEventType.of(name, id, DataType.uuid());
  }

}
