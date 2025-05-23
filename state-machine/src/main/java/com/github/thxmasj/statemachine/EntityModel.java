package com.github.thxmasj.statemachine;

import com.github.thxmasj.statemachine.database.MappingFailure;
import com.github.thxmasj.statemachine.database.mssql.SchemaNames.SecondaryIdModel;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

public interface EntityModel {

  String name();

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

  default List<Subscriber> subscribers() {
    return List.of();
  }

  default EventType eventType(int typeId) {
    return Stream.concat(eventTypes().stream(), Stream.of(BuiltinEventTypes.values()))
        .filter(type -> typeId == type.id())
        .findFirst().orElseThrow(() -> new MappingFailure("No event type has id " + typeId));
  }

  default EntityModel parentEntity() {
    return null;
  }

}
