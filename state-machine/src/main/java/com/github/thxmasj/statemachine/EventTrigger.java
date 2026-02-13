package com.github.thxmasj.statemachine;

import java.util.List;
import java.util.function.Function;
import java.util.stream.Stream;

public class EventTrigger<T, I, O> {

  public record EventSpec<T, I, O>(
      EventType<I, O> eventType,
      Function<T, I> inputAdapter
  ) {}

  private final EventSpec<T, I, O> eventSpec;
  private final List<? extends EntitySelector<T>> entitySelectors;
  private final EntityModel entityModel;
  private final boolean createEntity;

  public EventTrigger(
      EventSpec<T, I, O> eventSpec,
      List<? extends EntitySelector<T>> entitySelectors,
      EntityModel entityModel,
      boolean createEntity
  ) {
    this.eventSpec = eventSpec;
    this.entitySelectors = entitySelectors;
    this.entityModel = entityModel;
    this.createEntity = createEntity;
  }

  public EventSpec<T, I, O> eventSpec() {
    return eventSpec;
  }

  public EventTrigger<T, I, O> withFallbackSelector() {
    return new EventTrigger<>(eventSpec, replaceFallbackSelector(), entityModel(), createEntity());
  }

  private List<EntitySelector<T>> replaceFallbackSelector() {
    return Stream.concat(
        Stream.of(entitySelectors.getFirst().fallback()),
        entitySelectors.subList(1, entitySelectors.size()).stream()
    ).toList();
  }

  public List<? extends EntitySelector<T>> entitySelectors() {
    return entitySelectors;
  }

  public EntityModel entityModel() {
    return entityModel;
  }

  public boolean createEntity() {
    return createEntity;
  }

}
