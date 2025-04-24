package com.github.thxmasj.statemachine;

import com.github.thxmasj.statemachine.EventTrigger.EntitySelector;
import java.util.ArrayList;
import java.util.List;

public class EventTriggerBuilder {

  private final List<EntitySelector> entitySelectors = new ArrayList<>();
  private boolean create;
  private EventType eventType;
  private Object data;
  private EntityModel entityModel;

  public static EventTriggerBuilder event(EventType eventType, Object data) {
    var builder = new EventTriggerBuilder();
    builder.eventType = eventType;
    builder.data = data;
    return builder;
  }

  public static EventTriggerBuilder event(EventType eventType) {
    var builder = new EventTriggerBuilder();
    builder.eventType = eventType;
    return builder;
  }

  public EventTriggerBuilder onEntity(EntityModel entityModel) {
    this.entityModel = entityModel;
    return this;
  }

  public EventTriggerBuilder create() {
    this.create = true;
    return this;
  }

  public EventTriggerBuilder identifiedBy(EntitySelectorBuilder builder) {
    this.entitySelectors.add(builder.build());
    return this;
  }

  public EventTriggerBuilder and(EntitySelectorBuilder builder) {
    return identifiedBy(builder);
  }

  public EventTrigger build() {
    if (entitySelectors.isEmpty() && !create) throw new IllegalArgumentException("An entity selector must be specified unless create flag is set");
    return new EventTrigger(entitySelectors, eventType, data, entityModel, create);
  }

}
