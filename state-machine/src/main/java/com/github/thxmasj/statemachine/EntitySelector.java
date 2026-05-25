package com.github.thxmasj.statemachine;

import static com.github.thxmasj.statemachine.EntitySelector.CreationMode.AlwaysCreate;
import static com.github.thxmasj.statemachine.EntitySelector.CreationMode.NeverCreate;

import com.github.thxmasj.statemachine.EntitySelector.ById;
import com.github.thxmasj.statemachine.EntitySelector.ByIdFromSession;
import com.github.thxmasj.statemachine.EntitySelector.ByLastInIdGroup;
import com.github.thxmasj.statemachine.EntitySelector.ByNextInIdGroup;
import com.github.thxmasj.statemachine.EntitySelector.BySecondaryId;
import com.github.thxmasj.statemachine.database.mssql.SchemaNames;
import com.github.thxmasj.statemachine.database.mssql.SchemaNames.SecondaryIdModel;
import java.util.UUID;
import java.util.function.Function;

public sealed class EntitySelector permits ById, ByIdFromSession, ByLastInIdGroup, ByNextInIdGroup, BySecondaryId {

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

  public static <T> Function<T, ByIdFromSession> entityIdFromSession() {
    return _ -> new ByIdFromSession();
  }

  public static <T> BySecondaryId<T> secondaryId(SecondaryIdModel<T> model, T value) {
    return secondaryId(model, value, NeverCreate);
  }

  public static <T> BySecondaryId<T> secondaryId(SecondaryIdModel<T> model, T value, CreationMode creationMode) {
    return new BySecondaryId<>(model, value, creationMode);
  }

  public static <T> BySecondaryId<T> secondaryId(SecondaryIdModel<T> model, T value, EntitySelector fallback) {
    return new BySecondaryId<>(model, value, fallback);
  }

  public static <T> ByLastInIdGroup<T> lastInIdGroup(SecondaryIdModel<T> model, Object value) {
    return lastInIdGroup(model, value, NeverCreate);
  }

  public static <T> ByLastInIdGroup<T> lastInIdGroup(SecondaryIdModel<T> model, Object value, CreationMode creationMode) {
    return new ByLastInIdGroup<>(model, value, creationMode, 1);
  }

  public static <I, T> ByLastInIdGroup<T> secondToLastInIdGroup(SecondaryIdModel<T> model, Object value) {
    return new ByLastInIdGroup<>(model, value, NeverCreate, 2);
  }

  public static <I, T> ByLastInIdGroup<T> secondToLastInIdGroup(SecondaryIdModel<T> model, Object value,  CreationMode creationMode) {
    return new ByLastInIdGroup<>(model, value, creationMode, 2);
  }

  public static <I, T> ByNextInIdGroup<I, T> nextInIdGroup(SecondaryIdModel<T> model, CreationMode creationMode) {
    return new ByNextInIdGroup<>(model, creationMode);
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

  public static final class BySecondaryId<T> extends EntitySelector {

    private final SchemaNames.SecondaryIdModel<T> model;
    private final T value;

    public BySecondaryId(
        SchemaNames.SecondaryIdModel<T> model,
        T value,
        CreationMode creationMode
    ) {
      super(creationMode, null);
      this.model = model;
      this.value = value;
    }

    public BySecondaryId(
        SchemaNames.SecondaryIdModel<T> model,
        T value,
        EntitySelector fallback
    ) {
      super(NeverCreate, fallback);
      this.model = model;
      this.value = value;
    }

    public SchemaNames.SecondaryIdModel<T> model() {
      return model;
    }

    public T value() {
      return value;
    }

    @Override
    public String toString() {
      return getClass().getSimpleName() + "(" + model().name() + ", " + creationMode().name() + ")";
    }


  }

  public static final class ByLastInIdGroup<T> extends EntitySelector {

    private final SchemaNames.SecondaryIdModel<T> model;
    private final Object group;
    private final int lastPostition;

    public ByLastInIdGroup(
        SchemaNames.SecondaryIdModel<T> model,
        Object group,
        CreationMode creationMode,
        int lastPostition
    ) {
      super(creationMode, null);
      this.model = model;
      this.group = group;
      this.lastPostition = lastPostition;
    }

    public SecondaryIdModel<T> model() {
      return model;
    }

    public Object group() {
      return group;
    }

    public int lastPosition() {
      return lastPostition;
    }
  }

  public static final class ByNextInIdGroup<I, T> extends EntitySelector {

    private final SchemaNames.SecondaryIdModel<T> model;

    public ByNextInIdGroup(SchemaNames.SecondaryIdModel<T> model, CreationMode creationMode) {
      super(creationMode, null);
      this.model = model;
    }

    public SecondaryIdModel<T> model() {
      return model;
    }
  }

}
