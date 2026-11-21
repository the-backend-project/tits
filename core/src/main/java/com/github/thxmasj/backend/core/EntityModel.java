package com.github.thxmasj.backend.core;

import com.github.thxmasj.backend.core.internal.EntityModels;
import com.github.thxmasj.backend.core.internal.Memoized;
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

  /// Whether the entities of the model are stored. The entities of a model which is not stored are entities of the
  /// session only: their transitions are taken, and the events they trigger on other entities are stored if those
  /// are, but none of their own events are stored, and they can't be found after the session. Their transitions may
  /// therefore go to any state, but they can't have delayed or conditional triggers, which fire after the session.
  default boolean stored() {
    return true;
  }

  /// An entity model whose transitions are created when they are first asked for, so that they can refer to the model
  /// itself and to other models created the same way, e.g. as constants which refer to each other.
  static EntityModel of(String name, UUID id, Supplier<Map<State, List<TransitionModel<?, ?>>>> transitions) {
    var created = new Memoized<>(transitions, "transitions of entity model " + name);
    return new EntityModel() {
      @Override public String name() {return name;}
      @Override public UUID id() {return id;}
      @Override public Map<State, List<TransitionModel<?, ?>>> transitions() {return created.get();}
      @Override public String toString() {return name;}
    };
  }

  /// An entity model which is not stored, whose entities are of the session only (see [#stored()]), with its transitions
  /// created when they are first asked for, as [#of(String, UUID, Supplier)]
  static EntityModel notStored(String name, UUID id, Supplier<Map<State, List<TransitionModel<?, ?>>>> transitions) {
    var created = new Memoized<>(transitions, "transitions of entity model " + name);
    return new EntityModel() {
      @Override public String name() {return name;}
      @Override public UUID id() {return id;}
      @Override public Map<State, List<TransitionModel<?, ?>>> transitions() {return created.get();}
      @Override public boolean stored() {return false;}
      @Override public String toString() {return name;}
    };
  }

  /// The entity models, and those their transitions trigger events on, directly, in reversals and in the transitions they
  /// may choose, and so on, each once: the models a state machine given the entity models runs. A group triggered on
  /// adds no models, as its models are those of the group which are run. The models found follow the ones given.
  ///
  /// @throws IllegalArgumentException if one of the entity models given is an [EntityModelGroup], or if two of the
  ///                                  models have the same id
  /// @throws NullPointerException if one of the entity models given is null, e.g. a constant not created yet
  static List<EntityModel> reachable(List<? extends EntityModel> entityModels) {
    return EntityModels.resolve(entityModels);
  }
}
