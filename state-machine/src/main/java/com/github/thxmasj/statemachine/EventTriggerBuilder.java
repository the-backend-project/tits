package com.github.thxmasj.statemachine;

import com.github.thxmasj.statemachine.EventTrigger.EntitySelector;
import java.util.ArrayList;
import java.util.List;

public class EventTriggerBuilder<I, O> {

  private final List<EntitySelector> entitySelectors = new ArrayList<>();
  private boolean create;
  private final EventType<I, O> eventType;
  private I data;
  private EntityModel entityModel;

  private EventTriggerBuilder(EventType<I, O> et) {
    this.eventType = et;
  }

  public static <I, O> EventTriggerBuilder<I, O> event(EventType<I, O> eventType, I data) {
    var builder = new EventTriggerBuilder<>(eventType);
    builder.data = data;
    return builder;
  }

  public static <I, O> EventTriggerBuilder<I, O> event(EventType<I, O> eventType) {
    return new EventTriggerBuilder<>(eventType);
  }

  public EventTriggerBuilder<I, O> onEntity(EntityModel entityModel) {
    this.entityModel = entityModel;
    return this;
  }

  public EventTriggerBuilder<I, O> create() {
    this.create = true;
    return this;
  }

  public EventTriggerBuilder<I, O> identifiedBy(EntitySelectorBuilder builder) {
    this.entitySelectors.add(builder.build());
    return this;
  }

  public EventTriggerBuilder<I, O> and(EntitySelectorBuilder builder) {
    return identifiedBy(builder);
  }

  public EventTrigger<I> build() {
    if (entitySelectors.isEmpty() && !create) throw new IllegalArgumentException("An entity selector must be specified unless create flag is set");
    return new EventTrigger<>(entitySelectors, eventType, data, entityModel, create);
  }

}
