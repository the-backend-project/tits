package com.github.thxmasj.statemachine;

import com.github.thxmasj.statemachine.database.MappingFailure;
import com.github.thxmasj.statemachine.database.mssql.SchemaNames.SecondaryIdModel;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

public interface EntityModel {

  String name();

  UUID id();

  default TraversableState begin() {
    return TraversableState.create(this);
  }

  default EntityId newEntityId() {
    return new EntityId.UUID(UUID.randomUUID());
  }

  default List<SecondaryIdModel> secondaryIds() {
    return List.of();
  }

  List<EventType> eventTypes();

  State initialState();

  List<TransitionModel<?>> transitions();

  default List<OutboxQueue> queues() {
    return List.of();
  }

  default EventType eventType(UUID typeId) {
    return Stream.concat(eventTypes().stream(), Stream.of(BuiltinEventTypes.values()))
        .filter(type -> typeId.equals(type.id()))
        .findFirst().orElseThrow(() -> new MappingFailure("No event type has id " + typeId));
  }

  default EntityModel parentEntity() {
    return null;
  }

}
