package com.github.thxmasj.statemachine;

import com.github.thxmasj.statemachine.database.mssql.SchemaNames.SecondaryIdModel;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Stream;

import static com.github.thxmasj.statemachine.EntitySelector.entityId;
import static com.github.thxmasj.statemachine.EntitySelector.newEntityId;
import static com.github.thxmasj.statemachine.EntitySelector.secondaryId;
import static java.util.stream.Collectors.toList;

public class EventTrigger<T, I, O> {

  public record EventSpec<T, I, O>(
      EventType<I, O> eventType,
      Function<T, I> inputAdapter
  ) {}

  private final EventSpec<T, I, O> eventSpec;
  private final List<Function<T, ? extends EntitySelector>> entitySelectors;
  private final EntityModel entityModel;
  private final boolean createEntity;

  public EventTrigger(
      EventSpec<T, I, O> eventSpec,
      List<Function<T, ? extends EntitySelector>> entitySelectors,
      EntityModel entityModel,
      boolean createEntity
  ) {
    this.eventSpec = eventSpec;
    this.entitySelectors = entitySelectors;
    this.entityModel = entityModel;
    this.createEntity = createEntity;
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

  public EventTrigger<T, I, O> withFallbackSelector() {
    return new EventTrigger<>(eventSpec, replaceFallbackSelector(), entityModel(), createEntity());
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

}
