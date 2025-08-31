package com.github.thxmasj.statemachine;

import com.github.thxmasj.statemachine.database.mssql.SchemaNames.SecondaryIdModel;
import java.util.List;

public class EventTrigger<I> {

  public record EntitySelector(
      EntityId entityId,
      Object group,
      SecondaryId secondaryId,
      SecondaryIdModel secondaryIdModel,
      String messageId,
      int last,
      boolean create,
      boolean createIfNotExists,
      EntitySelector fallback,
      boolean next
  ) {}

  private final List<EntitySelector> entitySelectors;
  private final EventType<I, ?> eventType;
  private final I data;
  private final EntityModel entityModel;
  private final boolean createEntity;

  public EventTrigger(
      List<EntitySelector> entitySelectors,
      EventType<I, ?> eventType,
      I data,
      EntityModel entityModel,
      boolean createEntity
  ) {
    this.entitySelectors = entitySelectors;
    this.eventType = eventType;
    this.data = data;
    this.entityModel = entityModel;
    this.createEntity = createEntity;
  }

  public List<EntitySelector> entitySelectors() {
    return entitySelectors;
  }

  public EventType<I, ?> eventType() {
    return eventType;
  }

  public I data() {
    return data;
  }

  public EntityModel entityModel() {
    return entityModel;
  }

  public boolean createEntity() {
    return createEntity;
  }

}
