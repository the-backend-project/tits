package com.github.thxmasj.backend.core.internal;

import static com.github.thxmasj.backend.core.EntityModel.Begin;
import static com.github.thxmasj.backend.core.EntitySelector.entityIdFromSession;
import static com.github.thxmasj.backend.core.EntitySelector.newEntityId;
import static com.github.thxmasj.backend.core.TransitionModelBuilder.WithEvent.onEvent;
import static com.github.thxmasj.backend.core.TransitionModelBuilder.assemble;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.thxmasj.backend.core.BasicEventType;
import com.github.thxmasj.backend.core.EntityModel;
import com.github.thxmasj.backend.core.EntityModelGroup;
import com.github.thxmasj.backend.core.EventType;
import com.github.thxmasj.backend.core.TransitionModelBuilder.TransitionContext;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

public class EntityModelsTest {

  static final EventType<Void, Void> Started = BasicEventType.of("Started", UUID.randomUUID());
  static final EventType<Void, Void> Ordered = BasicEventType.of("Ordered", UUID.randomUUID());
  static final EventType<Void, Void> Undone = BasicEventType.of("Undone", UUID.randomUUID());
  static final EventType<Void, Void> Chosen = BasicEventType.of("Chosen", UUID.randomUUID());

  static final EntityModel Order = EntityModel.of("Order", UUID.randomUUID(), () -> Map.of(Begin, List.of(
      onEvent(Ordered).toSelf().output()
  )));
  static final EntityModelGroup Orders = EntityModelGroup.of("Orders", UUID.randomUUID(), m -> m == Order);

  /// A process triggering an event on the model, directly, in its reversal or in a transition it may choose
  private static EntityModel process(EntityModel target, String how) {
    return EntityModel.of("Process", UUID.randomUUID(), () -> Map.of(Begin, List.of(switch (how) {
      case "trigger" -> onEvent(Started).toSelf().trigger(Ordered).on(target).identifiedBy(newEntityId()).output();
      case "reversal" -> onEvent(Started).toSelf()
          .assemble(TransitionContext::entityId)
          .reversible(assemble((_, _) -> UUID.randomUUID())
              .trigger(Undone).on(target).identifiedBy(newEntityId()))
          .output();
      case "choice" -> onEvent(Started).toSelf().assembleInput()
          .otherwise(onEvent(Chosen).toSelf().trigger(Ordered).on(target).identifiedBy(newEntityId()).output(), d -> d);
      default -> throw new IllegalArgumentException(how);
    })));
  }

  @Test
  public void modelGivenMoreThanOnceIsKeptOnce() {
    assertThat(EntityModels.validated(List.of(Order, Order))).containsExactly(Order);
  }

  @Test
  public void groupIsRejected() {
    assertThatThrownBy(() -> EntityModels.validated(List.of(Order, Orders)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Entity model group Orders has no entities of its own");
  }

  @Test
  public void modelsWithTheSameIdAreRejected() {
    var copy = EntityModel.of("Copy", Order.id());
    assertThatThrownBy(() -> EntityModels.validated(List.of(Order, copy)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Entity models Order and Copy have the same id " + Order.id());
  }

  @Test
  public void triggersOnModelsGivenOrTheirGroupsPass() {
    var process = process(Order, "trigger");
    var toGroup = EntityModel.of("Responder", UUID.randomUUID(), () -> Map.of(Begin, List.of(
        onEvent(Started).toSelf().trigger(Ordered).on(Orders).identifiedBy(entityIdFromSession()).output()
    )));
    assertThat(EntityModels.validated(List.of(process, toGroup, Order))).containsExactly(process, toGroup, Order);
  }

  @Test
  public void triggerOnModelNotGivenIsRejected() {
    for (String how : List.of("trigger", "reversal", "choice"))
      assertThatThrownBy(() -> EntityModels.validated(List.of(process(Order, how))))
          .as(how)
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("triggers")
          .hasMessageContaining("on Order, which is not one of the entity models given to the state machine")
          .hasMessageNotContaining("Another entity model with its id");
  }

  /// E.g. a trigger on an outbox request rather than on the copy of it its dispatcher has
  @Test
  public void triggerOnAnotherModelWithTheIdOfAModelGivenIsRejected() {
    var copy = EntityModel.of("Order", Order.id(), Order::transitions);
    assertThatThrownBy(() -> EntityModels.validated(List.of(process(Order, "trigger"), copy)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Started on Process triggers Ordered on Order, which is not one of the entity models given to the "
            + "state machine. Another entity model with its id is, which the event is to be triggered on instead, e.g. "
            + "the outbox() of the dispatcher of an outbox request");
  }

}
