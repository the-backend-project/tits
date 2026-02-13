package com.github.thxmasj.statemachine.database;

import com.github.thxmasj.statemachine.EntityId;
import com.github.thxmasj.statemachine.EntityModel;
import com.github.thxmasj.statemachine.SecondaryId;

public class UnknownEntity extends RuntimeException {

  private final EntityModel entityModel;
  private final SecondaryId secondaryId;
  private final EntityId id;
  private final String messageId;

  public UnknownEntity(EntityModel entityModel, SecondaryId id) {
    super(String.format("Unknown entity: %s/%s=%s", entityModel.name(), id.model().name(), id));
    this.secondaryId = id;
    this.entityModel = entityModel;
    this.id = null;
    this.messageId = null;
  }

  public UnknownEntity(EntityModel entityModel, EntityId id, String sql) {
    super(String.format("Unknown entity: %s/id=%s", entityModel.name(), id.value()), new RuntimeException(sql));
    this.entityModel = entityModel;
    this.secondaryId = null;
    this.id = id;
    this.messageId = null;
  }

  public UnknownEntity(String messageId) {
    super("No entity found with an incoming request " + messageId);
    this.secondaryId = null;
    this.entityModel = null;
    this.id = null;
    this.messageId = messageId;
  }

  public SecondaryId secondaryId() {
    return secondaryId;
  }

  public EntityId id() {
    return id;
  }

  public String messageId() {
    return messageId;
  }

}
