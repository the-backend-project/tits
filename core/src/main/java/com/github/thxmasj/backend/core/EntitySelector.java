package com.github.thxmasj.backend.core;

import static com.github.thxmasj.backend.core.EntitySelector.CreationMode.AlwaysCreate;
import static com.github.thxmasj.backend.core.EntitySelector.CreationMode.NeverCreate;

import com.github.thxmasj.backend.core.EntitySelector.ById;
import com.github.thxmasj.backend.core.EntitySelector.ByIdFromSession;
import com.github.thxmasj.backend.core.EntitySelector.ByIdEvent;
import com.github.thxmasj.backend.core.EntitySelector.ByLastIndexed;
import java.util.UUID;
import java.util.function.Function;

/// Selects the entity an event is triggered on
///
/// A selector that may create an entity is given its id as an [EntityId], which can't be made from a UUID: the id is
/// new and time-ordered, or the id of the entity a transition is on. An existing entity can be selected by any id. See
/// [identifiers](https://the-backend-project.github.io/tits/identifiers/).
public sealed class EntitySelector permits ById, ByIdFromSession, ByIdEvent, ByLastIndexed {

  /// The existing entity with the id
  public static ById entityId(UUID entityId) {
    return new ById(entityId, NeverCreate);
  }

  /// The existing entity with the id
  public static ById entityId(EntityId entityId) {
    return entityId(entityId.value());
  }

  /// The entity with the id, which is created with it, depending on the creation mode, if it does not exist
  public static ById entityId(EntityId entityId, CreationMode creationMode) {
    return new ById(entityId.value(), creationMode);
  }

  /// A new entity, with a new time-ordered id
  public static <T> Function<T, ById> newEntityId() {
    return _ -> newEntity();
  }

  /// A new entity, with a new time-ordered id
  public static ById newEntity() {
    return new ById(Identifiers.newId(), AlwaysCreate);
  }

  /// A new entity with the id, e.g. an index entity with the id of the entity a transition is on
  public static ById newEntityId(EntityId entityId) {
    return new ById(entityId.value(), AlwaysCreate);
  }

  public static <T> Function<T, ByIdFromSession> entityIdFromSession() {
    return _ -> new ByIdFromSession();
  }

  /// The entity having the id, i.e. having an id event of the type with the id as data
  public static <T> ByIdEvent<T> idEvent(EventType<?, T> type, T id) {
    return idEvent(type, id, NeverCreate);
  }

  public static <T> ByIdEvent<T> idEvent(EventType<?, T> type, T id, CreationMode creationMode) {
    return new ByIdEvent<>(type, id, creationMode, null);
  }

  public static <T> ByIdEvent<T> idEvent(EventType<?, T> type, T id, EntitySelector fallback) {
    return new ByIdEvent<>(type, id, NeverCreate, fallback);
  }

  /// The entity with the last index event of a group, i.e. the last of the index events of the type whose data is in
  /// the group, as ordered by the type's GroupedDataType
  public static <G> ByLastIndexed<G> lastIndexed(EventType<?, ?> type, G group) {
    return lastIndexed(type, group, NeverCreate);
  }

  public static <G> ByLastIndexed<G> lastIndexed(EventType<?, ?> type, G group, CreationMode creationMode) {
    return new ByLastIndexed<>(type, group, creationMode, 1);
  }

  /// The entity with the index event before the last one of a group, or the last one if it is the only one
  public static <G> ByLastIndexed<G> secondToLastIndexed(EventType<?, ?> type, G group) {
    return secondToLastIndexed(type, group, NeverCreate);
  }

  public static <G> ByLastIndexed<G> secondToLastIndexed(EventType<?, ?> type, G group, CreationMode creationMode) {
    return new ByLastIndexed<>(type, group, creationMode, 2);
  }

  public enum CreationMode{AlwaysCreate, CreateIfNotExists, NeverCreate}

  private final CreationMode creationMode;
  private final EntitySelector fallback;

  protected EntitySelector(CreationMode creationMode, EntitySelector fallback) {
    this.creationMode = creationMode;
    this.fallback = fallback;
  }

  public CreationMode creationMode() {
    return creationMode;
  }

  public EntitySelector fallback() {
    return fallback;
  }

  public static final class ById extends EntitySelector {

    private final UUID entityId;

    /// Not public, as a selector creating an entity must not be given an id from elsewhere than [EntityId]
    ById(UUID entityId, CreationMode creationMode) {
      super(creationMode, null);
      this.entityId = entityId;
    }

    public UUID id() {
      return entityId;
    }

    @Override
    public String toString() {
      return getClass().getSimpleName() + "(" + entityId + ", " + creationMode().name() + ")";
    }

  }

  public static final class ByIdFromSession extends EntitySelector {

    public ByIdFromSession() {
      super(NeverCreate, null);
    }

  }

  public static final class ByIdEvent<T> extends EntitySelector {

    private final EventType<?, T> type;
    private final T id;

    public ByIdEvent(EventType<?, T> type, T id, CreationMode creationMode, EntitySelector fallback) {
      super(creationMode, fallback);
      this.type = type;
      this.id = id;
    }

    public EventType<?, T> type() {
      return type;
    }

    public T id() {
      return id;
    }

    @Override
    public String toString() {
      return getClass().getSimpleName() + "(" + type.name() + ", " + creationMode().name() + ")";
    }
  }

  public static final class ByLastIndexed<G> extends EntitySelector {

    private final EventType<?, ?> type;
    private final G group;
    private final int position;

    /// @param position 1 for the last entity, 2 for the one before it, and so on
    public ByLastIndexed(EventType<?, ?> type, G group, CreationMode creationMode, int position) {
      super(creationMode, null);
      this.type = type;
      this.group = group;
      this.position = position;
    }

    public EventType<?, ?> type() {
      return type;
    }

    public G group() {
      return group;
    }

    public int position() {
      return position;
    }
  }

}
