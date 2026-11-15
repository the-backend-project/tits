package com.github.thxmasj.backend.core;

import java.util.UUID;

/// The id of an entity to be created, for the entity selectors that may create one
///
/// It can't be made from a UUID: it is either a new time-ordered id, from [#newId()], or the id of the entity a
/// transition is on, from [TransitionModelBuilder.TransitionContext#entityId()]. An entity is therefore never created
/// with an id from elsewhere, e.g. a random UUID or one taken from a request, which would be inserted at a random place
/// in the indexes the entity ids lead. Elsewhere entity ids are plain UUIDs. See
/// [identifiers](https://the-backend-project.github.io/tits/identifiers/).
public final class EntityId {

  private final UUID value;

  private EntityId(UUID value) {
    this.value = value;
  }

  /// A new time-ordered id, see [Identifiers#newId()]
  public static EntityId newId() {
    return new EntityId(Identifiers.newId());
  }

  /// The id of an existing entity, only to be given ids the state machine has read or created
  static EntityId of(UUID value) {
    return new EntityId(value);
  }

  public UUID value() {
    return value;
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof EntityId other && value.equals(other.value);
  }

  @Override
  public int hashCode() {
    return value.hashCode();
  }

  @Override
  public String toString() {
    return value.toString();
  }

}
