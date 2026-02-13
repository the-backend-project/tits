package com.github.thxmasj.statemachine;

import com.github.thxmasj.statemachine.EventTrigger.EventSpec;
import com.github.thxmasj.statemachine.message.http.HttpRequestMessage;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

public class EventTriggerBuilder<I, O> {

  private final List<EntitySelector<HttpRequestMessage>> entitySelectors = new ArrayList<>();
  private boolean create;
  private final EventType<I, O> eventType;
  private final Function<HttpRequestMessage, I> eventData;
  private EntityModel entityModel;

  private EventTriggerBuilder(EventType<I, O> et, Function<HttpRequestMessage, I> eventData) {
    this.eventType = et;
    this.eventData = eventData;
  }

  public static <I, O> EventTriggerBuilder<I, O> event(
      EventType<I, O> eventType,
      Function<HttpRequestMessage, I> eventData
  ) {
    return new EventTriggerBuilder<>(eventType, eventData);
  }

  public static <O> EventTriggerBuilder<Void, O> event(
      EventType<Void, O> eventType
  ) {
    return new EventTriggerBuilder<>(eventType, _ -> null);
  }

  public EventTriggerBuilder<I, O> onEntity(EntityModel entityModel) {
    this.entityModel = entityModel;
    return this;
  }

  public EventTriggerBuilder<I, O> create() {
    this.create = true;
    return this;
  }

  public EventTriggerBuilder<I, O> identifiedBy(EntitySelector<HttpRequestMessage> selector) {
    this.entitySelectors.add(selector);
    return this;
  }

  public EventTriggerBuilder<I, O> and(EntitySelector<HttpRequestMessage> selector) {
    return identifiedBy(selector);
  }

  public EventTrigger<HttpRequestMessage, I, O> build() {
    if (entitySelectors.isEmpty() && !create) throw new IllegalArgumentException("An entity selector must be specified unless create flag is set");
    return new EventTrigger<>(new EventSpec<>(eventType, eventData), entitySelectors, entityModel, create);
  }

}
