package com.github.thxmasj.backend.core.test;

import static com.github.thxmasj.backend.core.EntityModel.Begin;
import static com.github.thxmasj.backend.core.EntitySelector.CreationMode.AlwaysCreate;
import static com.github.thxmasj.backend.core.EntitySelector.CreationMode.CreateIfNotExists;
import static com.github.thxmasj.backend.core.EntitySelector.entityId;
import static com.github.thxmasj.backend.core.EntitySelector.newEntityId;
import static com.github.thxmasj.backend.core.EventTrigger.trigger;
import static com.github.thxmasj.backend.core.TransitionModelBuilder.WithEvent.onEvent;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.github.thxmasj.backend.core.BasicEventType;
import com.github.thxmasj.backend.core.DataType;
import com.github.thxmasj.backend.core.EntityModel;
import com.github.thxmasj.backend.core.EntitySelector.CreationMode;
import com.github.thxmasj.backend.core.Event;
import com.github.thxmasj.backend.core.EventTrigger;
import com.github.thxmasj.backend.core.EventTrigger.EventSpec;
import com.github.thxmasj.backend.core.EventType;
import com.github.thxmasj.backend.core.Identifiers;
import com.github.thxmasj.backend.core.IndexEntityModel;
import com.github.thxmasj.backend.core.State;
import com.github.thxmasj.backend.core.StateMachine;
import com.github.thxmasj.backend.core.StateMachine.RejectedEvent;
import com.github.thxmasj.backend.core.StateMachine.RejectedEvent.Reason;
import com.github.thxmasj.backend.core.TransitionModel;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/// New entities must get time-ordered ids
@Tag("database")
public class EntityIdTest {

  enum States implements State {Created}

  static EventType<UUID, UUID> OrderReferenced = IndexEntityModel.referenceEventType("Order referenced", UUID.fromString("4b0e6f2a-9c1d-4e3b-8a5f-7d2c6e1b9a04"));
  static IndexEntityModel Index = IndexEntityModel.of("Index", UUID.fromString("c6a2e8f4-3b7d-4c1e-9f5a-0d8b2e6c4a17"), OrderReferenced);

  static EntityModel Order = EntityModel.of("Order", UUID.fromString("8e4c2a6f-1d3b-4f5e-a7c9-2b0d4f6e8a31"));
  static EventType<UUID, String> OrderCreated = BasicEventType.of("Order created", UUID.fromString("1f3d5b7e-9a2c-4e6f-8b0d-3c5e7a9f1b26"), DataType.uuid(), DataType.string());
  /// Creates an index entity with the id given as input
  static EventType<UUID, String> OrderIndexed = BasicEventType.of("Order indexed", UUID.fromString("7a9c1e3f-5b2d-4a8c-9e0f-6d1b3f5a7c48"), DataType.uuid(), DataType.string());

  private static Map<EntityModel, Map<State, List<TransitionModel<?, ?>>>> transitions() {
    return Map.of(
        Order, Map.of(
            Begin, List.of(
                onEvent(OrderCreated).to(States.Created).assembleInput().output(_ -> "created"),
                onEvent(OrderIndexed).to(States.Created)
                    .assembleInput()
                    .trigger(OrderReferenced).with(_ -> UUID.randomUUID()).on(Index).identifiedBy(id -> newEntityId(id))
                    .output(d -> d.t2().isRejected() ? d.t2().rejected().exception().reason().name() : "indexed")
            ),
            States.Created, List.of()
        ),
        Index, Index.transitions()
    );
  }

  private static final StateMachine machine = Init.stateMachine(transitions());

  private static EventTrigger<UUID, UUID, String> create(UUID id, CreationMode creationMode) {
    return new EventTrigger<>(new EventSpec<>(OrderCreated, d -> d), List.of(_ -> entityId(id, creationMode)), Order, false);
  }

  @Test
  public void entityIsCreatedWithATimeOrderedId() {
    UUID id = Identifiers.newId();

    Event<?> created = machine.onEvent(create(id, AlwaysCreate), id).blockFirst();

    assertEquals(id, created.entityId());
  }

  @Test
  public void entityIsCreatedIfNotExistsWithATimeOrderedId() {
    UUID id = Identifiers.newId();

    Event<?> created = machine.onEvent(create(id, CreateIfNotExists), id).blockFirst();

    assertEquals(id, created.entityId());
  }

  @Test
  public void eventIsRejectedWhenAnEntityIsCreatedWithARandomId() {
    UUID id = UUID.randomUUID();

    for (var creationMode : List.of(AlwaysCreate, CreateIfNotExists)) {
      RejectedEvent rejected = assertThrows(
          RejectedEvent.class,
          () -> machine.onEvent(create(id, creationMode), id).blockFirst(),
          creationMode.name()
      );
      assertEquals(Reason.InvalidEntityId, rejected.reason());
      assertEquals(Order, rejected.entityModel());
      assertEquals(id, rejected.entityId());
    }
  }

  @Test
  public void triggerIsRejectedWhenItCreatesAnEntityWithARandomId() {
    Event<?> created = machine.onEvent(trigger(OrderIndexed, Order), UUID.randomUUID()).blockFirst();

    assertEquals(Reason.InvalidEntityId.name(), created.getUnmarshalledData());
  }

  @Test
  public void triggerCreatesAnEntityWithATimeOrderedId() {
    Event<?> created = machine.onEvent(trigger(OrderIndexed, Order), Identifiers.newId()).blockFirst();

    assertEquals("indexed", created.getUnmarshalledData());
  }

}
