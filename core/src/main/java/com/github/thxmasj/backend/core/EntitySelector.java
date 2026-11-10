package com.github.thxmasj.backend.core;

import static com.github.thxmasj.backend.core.EntitySelector.CreationMode.AlwaysCreate;
import static com.github.thxmasj.backend.core.EntitySelector.CreationMode.CreateIfNotExists;
import static com.github.thxmasj.backend.core.EntitySelector.CreationMode.NeverCreate;

import com.github.thxmasj.backend.core.EntitySelector.ById;
import com.github.thxmasj.backend.core.EntitySelector.ByIdFromSession;
import com.github.thxmasj.backend.core.EntitySelector.ByIdEvent;
import com.github.thxmasj.backend.core.EntitySelector.ByLastIndexed;
import com.github.thxmasj.backend.core.EntitySelector.BySameEntityId;
import java.util.UUID;
import java.util.function.Function;

public sealed class EntitySelector permits ById, ByIdFromSession, ByIdEvent, ByLastIndexed, BySameEntityId {

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

  /// The entity with the same entity id as the entity triggering the event, e.g. its id entity or index entity
  public static <T> Function<T, BySameEntityId> sameEntityId() {
    return _ -> new BySameEntityId();
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

  /// The entity last indexed by the key, i.e. having the last index event of the type with the key as data
  public static <T> ByLastIndexed<T> lastIndexed(EventType<?, T> type, T key) {
    return lastIndexed(type, key, NeverCreate);
  }

  public static <T> ByLastIndexed<T> lastIndexed(EventType<?, T> type, T key, CreationMode creationMode) {
    return new ByLastIndexed<>(type, key, creationMode);
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

  public static final class BySameEntityId extends EntitySelector {
    public BySameEntityId() {
      super(CreateIfNotExists, null);
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

  public static final class ByLastIndexed<T> extends EntitySelector {

    private final EventType<?, T> type;
    private final T key;

    public ByLastIndexed(EventType<?, T> type, T key, CreationMode creationMode) {
      super(creationMode, null);
      this.type = type;
      this.key = key;
    }

    public EventType<?, T> type() {
      return type;
    }

    public T key() {
      return key;
    }
  }

}
