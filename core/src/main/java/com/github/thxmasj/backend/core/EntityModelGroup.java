package com.github.thxmasj.backend.core;

import java.util.UUID;
import java.util.function.Predicate;

/// Stands for any entity model of a group, as the target of a trigger, e.g. any HTTP inbox request. An event is
/// triggered on a group with the entity identified by the session ([EntitySelector#entityIdFromSession()]): it is
/// triggered on the entity of the session whose model is in the group, such as the inbox request an application
/// responds to, without the triggering transition knowing which model that is. A group has no entities or transitions
/// of its own, so it is not given to the state machine, and its entities can't be identified in any other way.
public interface EntityModelGroup extends EntityModel {

  /// Whether the entity model is in the group
  boolean includes(EntityModel entityModel);

  static EntityModelGroup of(String name, UUID id, Predicate<EntityModel> includes) {
    return new EntityModelGroup() {
      @Override public String name() {return name;}
      @Override public UUID id() {return id;}
      @Override public boolean includes(EntityModel entityModel) {return includes.test(entityModel);}
      @Override public String toString() {return name;}
    };
  }

  /// Whether a trigger on the target, an entity model or a group, can be taken by an entity of the model
  static boolean targets(EntityModel target, EntityModel entityModel) {
    return target.equals(entityModel) || target instanceof EntityModelGroup group && group.includes(entityModel);
  }

}
