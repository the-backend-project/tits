package com.github.thxmasj.statemachine;

import com.github.thxmasj.statemachine.EventTrigger.EntitySelector;
import com.github.thxmasj.statemachine.database.mssql.SchemaNames.SecondaryIdModel;
import javax.annotation.Nullable;
import java.util.UUID;

import static java.util.Objects.requireNonNull;

public class EntitySelectorBuilder {

  private EntityId entityId;
  private Object group;
  private SecondaryId secondaryId;
  private SecondaryIdModel secondaryIdModel;
  private String messageId;
  private int last = 0;
  private boolean createIfNotExists;
  private boolean create;
  private EntitySelector fallback;
  private boolean next;

  public static EntitySelectorBuilder id(EntityId id) {
    var builder = new EntitySelectorBuilder();
    builder.entityId = id;
    return builder;
  }

  public static EntitySelectorBuilder id(@Nullable String id) {
    requireNonNull(id);
    var builder = new EntitySelectorBuilder();
    builder.entityId = new EntityId.UUID(UUID.fromString(id));
    return builder;
  }

  public EntitySelectorBuilder group(Object group) {
    this.group = group;
    return this;
  }

  public static EntitySelectorBuilder id(SecondaryIdModel model, Object value) {
    var builder = new EntitySelectorBuilder();
    builder.secondaryId = new SecondaryId(model, value);
    return builder;
  }

  public static EntitySelectorBuilder model(SecondaryIdModel secondaryIdModel) {
    var builder = new EntitySelectorBuilder();
    builder.secondaryIdModel = secondaryIdModel;
    return builder;
  }

  public static EntitySelectorBuilder messageId(String messageId) {
    var builder = new EntitySelectorBuilder();
    builder.messageId = messageId;
    return builder;
  }

  public EntitySelectorBuilder last() {
    this.last = 1;
    return this;
  }

  public EntitySelectorBuilder last(int position) {
    this.last = position;
    return this;
  }

  public EntitySelectorBuilder createIfNotExists() {
    this.createIfNotExists = true;
    return this;
  }

  public EntitySelectorBuilder create() {
    this.create = true;
    return this;
  }

  public EntitySelectorBuilder ifNotExists(EntitySelectorBuilder fallback) {
    this.fallback = fallback.build();
    return this;
  }

  public EntitySelectorBuilder next() {
    this.next = true;
    return this;
  }

  public EntitySelector build() {
    return new EntitySelector(entityId, group, secondaryId, secondaryIdModel, messageId, last, create, createIfNotExists, fallback, next);
  }
}
