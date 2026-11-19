package com.github.thxmasj.backend.core;

import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/// A model of entities: the transitions an entity takes, by the state it is in. The state machine is given the entity
/// models it runs, and takes the transitions of each from the model itself.
public interface EntityModel extends Identifiable {

  State Begin = () -> "Begin";

  /// The transitions of the entities of the model, by the state they are taken from
  Map<State, List<TransitionModel<?, ?>>> transitions();

  /// An entity model whose transitions are created when they are first asked for, so that they can refer to the model
  /// itself and to other models created the same way, e.g. as constants which refer to each other.
  static EntityModel of(String name, UUID id, Supplier<Map<State, List<TransitionModel<?, ?>>>> transitions) {
    requireNonNull(transitions);
    return new EntityModel() {
      private Map<State, List<TransitionModel<?, ?>>> created;
      @Override public String name() {return name;}
      @Override public UUID id() {return id;}
      @Override public synchronized Map<State, List<TransitionModel<?, ?>>> transitions() {
        if (created == null)
          created = requireNonNull(transitions.get(), () -> "Entity model " + name + " has no transitions");
        return created;
      }
      @Override public String toString() {return name;}
    };
  }

  /// An entity model without transitions, whose entities are only created and identified, e.g. in tests
  static EntityModel of(String name, UUID id) {
    return of(name, id, Map::of);
  }
}
