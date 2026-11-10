package com.github.thxmasj.backend.core;

import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Stream;

import static com.github.thxmasj.backend.core.EntitySelector.entityId;
import static com.github.thxmasj.backend.core.EntitySelector.newEntityId;
import static com.github.thxmasj.backend.core.EntitySelector.secondaryId;
import static java.util.stream.Collectors.toList;

public class EventTrigger<T, I, O> {

  public record EventSpec<T, I, O>(
      EventType<I, O> eventType,
      Function<T, I> inputAdapter
  ) {}

  public record EventCondition<T>(
      EventType<?, ?> referenceType,
      Function<T, UUID> indexKey,
      List<EventType<?, ?>> lastTypes
  ) {}

  private final EventSpec<T, I, O> eventSpec;
  private final List<Function<T, ? extends EntitySelector>> entitySelectors;
  private final EntityModel entityModel;
  private final boolean createEntity;
  private final EventCondition<T> condition;
  private final TriggerType triggerType;
  private final UUID triggerId;

  public EventTrigger(
      EventSpec<T, I, O> eventSpec,
      List<Function<T, ? extends EntitySelector>> entitySelectors,
      EntityModel entityModel,
      boolean createEntity
  ) {
    this(eventSpec, entitySelectors, entityModel, createEntity, null);
  }

  public EventTrigger(
      EventSpec<T, I, O> eventSpec,
      List<Function<T, ? extends EntitySelector>> entitySelectors,
      EntityModel entityModel,
      boolean createEntity,
      EventCondition<T> condition
  ) {
    this(eventSpec, entitySelectors, entityModel, createEntity, condition, null, null);
  }

  /**
   * @param triggerType optional type of the stored trigger this event trigger originates from
   * @param triggerId   id of the stored trigger, present if and only if triggerType is
   */
  public EventTrigger(
      EventSpec<T, I, O> eventSpec,
      List<Function<T, ? extends EntitySelector>> entitySelectors,
      EntityModel entityModel,
      boolean createEntity,
      EventCondition<T> condition,
      TriggerType triggerType,
      UUID triggerId
  ) {
    if ((triggerType == null) != (triggerId == null))
      throw new IllegalArgumentException("Trigger type and trigger id must be given together");
    this.eventSpec = eventSpec;
    this.entitySelectors = entitySelectors;
    this.entityModel = entityModel;
    this.createEntity = createEntity;
    this.condition = condition;
    this.triggerType = triggerType;
    this.triggerId = triggerId;
  }

  public static <I, O> EventTrigger<I, I, O> trigger(EventType<I, O> eventType, EntityModel entityModel, UUID entityId, I data) {
    return new EventTrigger<>(new EventSpec<>(eventType, _ -> data), List.of(_ -> entityId(entityId)), entityModel, false);
  }

  public static <I, O> EventTrigger<I, I, O> trigger(EventType<I, O> eventType, EntityModel entityModel) {
    return new EventTrigger<>(new EventSpec<>(eventType, d -> d), List.of(newEntityId()), entityModel, false);
  }

  public static <I, O> EventTrigger<I, I, O> trigger(EventType<I, O> eventType, EntityModel entityModel, UUID entityId) {
    return new EventTrigger<>(new EventSpec<>(eventType, d -> d), List.of(_ -> entityId(entityId)), entityModel, false);
  }

  public static <I, O, ID> EventTrigger<I, I, O> trigger(EventType<I, O> eventType, EntityModel entityModel, SecondaryIdModel<ID> idModel, ID idValue) {
    return new EventTrigger<>(new EventSpec<>(eventType, d -> d), List.of(_ -> secondaryId(idModel, idValue)), entityModel, false);
  }

  public EventSpec<T, I, O> eventSpec() {
    return eventSpec;
  }

  /// This trigger with the input of the event adapted by the given adapter.
  public EventTrigger<T, I, O> withInputAdapter(Function<T, I> inputAdapter) {
    return new EventTrigger<>(new EventSpec<>(eventSpec.eventType(), inputAdapter), entitySelectors, entityModel, createEntity, condition, triggerType, triggerId);
  }

  public EventTrigger<T, I, O> withFallbackSelector() {
    return new EventTrigger<>(eventSpec, replaceFallbackSelector(), entityModel(), createEntity(), condition, triggerType, triggerId);
  }

  private List<Function<T, ? extends EntitySelector>> replaceFallbackSelector() {
    return Stream.concat(
        Stream.of(entitySelectors.getFirst().andThen(EntitySelector::fallback)),
        entitySelectors.subList(1, entitySelectors.size()).stream()
    ).collect(toList());
  }

  public List<Function<T, ? extends EntitySelector>> entitySelectors() {
    return entitySelectors;
  }

  public EntityModel entityModel() {
    return entityModel;
  }

  public boolean createEntity() {
    return createEntity;
  }

  public EventCondition<T> condition() {
    return condition;
  }

  public TriggerType triggerType() {
    return triggerType;
  }

  public UUID triggerId() {
    return triggerId;
  }

}
