package com.github.thxmasj.backend.core;

import static com.github.thxmasj.backend.core.EntitySelector.CreationMode.AlwaysCreate;
import static com.github.thxmasj.backend.core.EntitySelector.CreationMode.NeverCreate;

import com.github.thxmasj.backend.core.EntitySelector.ById;
import com.github.thxmasj.backend.core.EntitySelector.ByIdFromSession;
import com.github.thxmasj.backend.core.EntitySelector.ByIdEvent;
import com.github.thxmasj.backend.core.EntitySelector.ByLastInIdGroup;
import java.util.UUID;
import java.util.function.Function;

public sealed class EntitySelector permits ById, ByIdFromSession, ByIdEvent, ByLastInIdGroup {

  public static ById entityId(EntityId entityId) {
    return new ById(entityId, NeverCreate);
  }

  public static ById entityId(EntityId entityId, CreationMode creationMode) {
    return new ById(entityId, creationMode);
  }

  public static ById entityId(UUID entityId) {
    return new ById(new EntityId.UUID(entityId), NeverCreate);
  }

  public static ById entityId(UUID entityId, CreationMode creationMode) {
    return new ById(new EntityId.UUID(entityId), creationMode);
  }

  public static <T> Function<T, ById> newEntityId() {
    return _ -> new ById(new EntityId.UUID(UUID.randomUUID()), AlwaysCreate);
  }

  public static ById newEntityId(UUID id) {
    return new ById(new EntityId.UUID(id), AlwaysCreate);
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

  /// The entity with the last id of a group, i.e. the last of the id events of the type whose data is in the group, as
  /// ordered by the type's GroupedDataType
  public static <G> ByLastInIdGroup<G> lastInIdGroup(EventType<?, ?> type, G group) {
    return lastInIdGroup(type, group, NeverCreate);
  }

  public static <G> ByLastInIdGroup<G> lastInIdGroup(EventType<?, ?> type, G group, CreationMode creationMode) {
    return new ByLastInIdGroup<>(type, group, creationMode, 1);
  }

  /// The entity with the id before the last one of a group, or the last one if it is the only one
  public static <G> ByLastInIdGroup<G> secondToLastInIdGroup(EventType<?, ?> type, G group) {
    return new ByLastInIdGroup<>(type, group, NeverCreate, 2);
  }

  public static <G> ByLastInIdGroup<G> secondToLastInIdGroup(EventType<?, ?> type, G group,  CreationMode creationMode) {
    return new ByLastInIdGroup<>(type, group, creationMode, 2);
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

    private final EntityId entityId;

    public ById(EntityId entityId, CreationMode creationMode) {
      super(creationMode, null);
      this.entityId = entityId;
    }

    public EntityId id() {
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

  public static final class ByLastInIdGroup<G> extends EntitySelector {

    private final EventType<?, ?> type;
    private final G group;
    private final int lastPostition;

    public ByLastInIdGroup(
        EventType<?, ?> type,
        G group,
        CreationMode creationMode,
        int lastPostition
    ) {
      super(creationMode, null);
      this.type = type;
      this.group = group;
      this.lastPostition = lastPostition;
    }

    public EventType<?, ?> type() {
      return type;
    }

    public G group() {
      return group;
    }

    public int lastPosition() {
      return lastPostition;
    }
  }

}
