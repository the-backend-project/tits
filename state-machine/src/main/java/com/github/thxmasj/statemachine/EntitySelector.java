package com.github.thxmasj.statemachine;

import static com.github.thxmasj.statemachine.EntitySelector.CreationMode.AlwaysCreate;
import static com.github.thxmasj.statemachine.EntitySelector.CreationMode.NeverCreate;

import com.github.thxmasj.statemachine.EntitySelector.ById;
import com.github.thxmasj.statemachine.EntitySelector.ByIdFromSession;
import com.github.thxmasj.statemachine.EntitySelector.ByLastInIdGroup;
import com.github.thxmasj.statemachine.EntitySelector.ByMessageId;
import com.github.thxmasj.statemachine.EntitySelector.ByNextInIdGroup;
import com.github.thxmasj.statemachine.EntitySelector.BySecondaryId;
import com.github.thxmasj.statemachine.database.mssql.SchemaNames;
import com.github.thxmasj.statemachine.database.mssql.SchemaNames.SecondaryIdModel;
import java.util.UUID;
import java.util.function.Function;

public sealed class EntitySelector<I> permits ById, ByIdFromSession, ByLastInIdGroup, ByMessageId, ByNextInIdGroup,
    BySecondaryId {

  public static <I> ById<I> entityId(EntityId entityId) {
    return new ById<>(_ -> entityId, NeverCreate);
  }

  public static <I> ById<I> entityId(EntityId entityId, CreationMode creationMode) {
    return new ById<>(_ -> entityId, creationMode);
  }

  public static <I> ById<I> entityId(UUID entityId) {
    return new ById<>(_ -> new EntityId.UUID(entityId), NeverCreate);
  }

  public static <I> ById<I> newEntityId() {
    return new ById<>(_ -> new EntityId.UUID(UUID.randomUUID()), AlwaysCreate);
  }

  public static <I> ById<I> entityId(Function<I, UUID> entityId) {
    return new ById<>(entityId.andThen(EntityId.UUID::new), NeverCreate);
  }

  public static <I> ById<I> entityId(Function<I, UUID> entityId, CreationMode creationMode) {
    return new ById<>(entityId.andThen(EntityId.UUID::new), creationMode);
  }

  public static <I> ByIdFromSession<I> entityIdFromSession() {
    return new ByIdFromSession<>();
  }

  public static <I> BySecondaryId<I> secondaryId(SecondaryIdModel model, Function<I, Object> dataAdapter) {
    return secondaryId(model, dataAdapter, NeverCreate);
  }

  public static <I> BySecondaryId<I> secondaryId(SecondaryIdModel model, Function<I, Object> dataAdapter, CreationMode creationMode) {
    return new BySecondaryId<>(model, dataAdapter, creationMode);
  }

  public static <I> BySecondaryId<I> secondaryId(SecondaryIdModel model, Function<I, Object> dataAdapter, EntitySelector<I> fallback) {
    return new BySecondaryId<>(model, dataAdapter, fallback);
  }

  public static <I> ByLastInIdGroup<I> lastInIdGroup(SecondaryIdModel model, Function<I, Object> dataAdapter) {
    return lastInIdGroup(model, dataAdapter, NeverCreate);
  }

  public static <I> ByLastInIdGroup<I> lastInIdGroup(SecondaryIdModel model, Function<I, Object> dataAdapter, CreationMode creationMode) {
    return new ByLastInIdGroup<>(model, dataAdapter, creationMode, 1);
  }

  public static <I> ByLastInIdGroup<I> secondToLastInIdGroup(SecondaryIdModel model, Function<I, Object> dataAdapter) {
    return new ByLastInIdGroup<>(model, dataAdapter, NeverCreate, 2);
  }

  public static <I> ByNextInIdGroup<I> nextInIdGroup(SecondaryIdModel model, CreationMode creationMode) {
    return new ByNextInIdGroup<>(model, creationMode);
  }

  public static <I> ByMessageId<I> messageId(Function<I, String> dataAdapter) {
    return new ByMessageId<>(dataAdapter, NeverCreate);
  }

  public static <I> ByMessageId<I> messageId(Function<I, String> dataAdapter,  CreationMode creationMode) {
    return new ByMessageId<>(dataAdapter, creationMode);
  }

  public enum CreationMode{AlwaysCreate, CreateIfNotExists, NeverCreate};

  private final CreationMode creationMode;
  private final EntitySelector<I> fallback;

  protected EntitySelector(CreationMode creationMode, EntitySelector<I> fallback) {
    this.creationMode = creationMode;
    this.fallback = fallback;
  }

  public CreationMode creationMode() {
    return creationMode;
  }

  public EntitySelector<I> fallback() {
    return fallback;
  }

  public static final class ById<I> extends EntitySelector<I> {

    private final Function<I, EntityId> entityId;

    public ById(Function<I, EntityId> entityId, CreationMode creationMode) {
      super(creationMode, null);
      this.entityId = entityId;
    }

    public Function<I, EntityId> id() {
      return entityId;
    }

  }

  public static final class ByIdFromSession<I> extends EntitySelector<I> {

    public ByIdFromSession() {
      super(NeverCreate, null);
    }

  }

  public static final class BySecondaryId<I> extends EntitySelector<I> {

    private final SchemaNames.SecondaryIdModel model;
    private final Function<I, Object> id;

    public BySecondaryId(
        SchemaNames.SecondaryIdModel model,
        Function<I, Object> id,
        CreationMode creationMode
    ) {
      super(creationMode, null);
      this.model = model;
      this.id = id;
    }

    public BySecondaryId(
        SchemaNames.SecondaryIdModel model,
        Function<I, Object> id,
        EntitySelector<I> fallback
    ) {
      super(NeverCreate, fallback);
      this.model = model;
      this.id = id;
    }

    public SchemaNames.SecondaryIdModel model() {
      return model;
    }

    public Function<I, Object> id() {
      return id;
    }

  }

  public static final class ByLastInIdGroup<I> extends EntitySelector<I> {

    private final SchemaNames.SecondaryIdModel model;
    private final Function<I, Object> group;
    private final int lastPostition;

    public ByLastInIdGroup(
        SchemaNames.SecondaryIdModel model,
        Function<I, Object> group,
        CreationMode creationMode,
        int lastPostition
    ) {
      super(creationMode, null);
      this.model = model;
      this.group = group;
      this.lastPostition = lastPostition;
    }

    public SecondaryIdModel model() {
      return model;
    }

    public Function<I, Object> group() {
      return group;
    }

    public int lastPosition() {
      return lastPostition;
    }
  }

  public static final class ByNextInIdGroup<I> extends EntitySelector<I> {

    private final SchemaNames.SecondaryIdModel model;

    public ByNextInIdGroup(SchemaNames.SecondaryIdModel model, CreationMode creationMode) {
      super(creationMode, null);
      this.model = model;
    }

    public SecondaryIdModel model() {
      return model;
    }
  }

  public static final class ByMessageId<I> extends EntitySelector<I> {

    private final Function<I, String> messageId;

    public ByMessageId(Function<I, String> messageId, CreationMode creationMode) {
      super(creationMode, null);
      this.messageId = messageId;
    }

    public Function<I, String> messageId() {
      return messageId;
    }

  }

}
