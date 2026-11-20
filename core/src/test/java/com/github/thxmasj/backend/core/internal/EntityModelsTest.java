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

  /// A process triggering an event on the model, directly, in its reversal, in a transition it may choose, or in a
  /// transition its reversal may choose
  private static EntityModel process(EntityModel target, String how) {
    return EntityModel.of("Process", UUID.randomUUID(), () -> Map.of(Begin, List.of(switch (how) {
      case "trigger" -> onEvent(Started).toSelf().trigger(Ordered).on(target).identifiedBy(newEntityId()).output();
      case "reversal" -> onEvent(Started).toSelf()
          .assemble(TransitionContext::entityId)
          .reversible(assemble((_, _) -> UUID.randomUUID())
              .trigger(Undone).on(target).identifiedBy(newEntityId()))
          .output();
      case "choice in reversal" -> onEvent(Started).toSelf()
          .assemble(TransitionContext::entityId)
          .reversible(assemble((_, _) -> (Void) null)
              .when(_ -> true).then(onEvent(Chosen).toSelf().trigger(Undone).on(target).identifiedBy(newEntityId()).output()))
          .output();
      case "choice" -> onEvent(Started).toSelf().assembleInput()
          .otherwise(onEvent(Chosen).toSelf().trigger(Ordered).on(target).identifiedBy(newEntityId()).output(), d -> d);
      default -> throw new IllegalArgumentException(how);
    })));
  }

  @Test
  public void modelGivenMoreThanOnceIsKeptOnce() {
    assertThat(EntityModels.resolve(List.of(Order, Order))).containsExactly(Order);
  }

  @Test
  public void groupIsRejected() {
    assertThatThrownBy(() -> EntityModels.resolve(List.of(Order, Orders)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Entity model group Orders has no entities of its own");
  }

  @Test
  public void modelsWithTheSameIdAreRejected() {
    var copy = EntityModel.of("Copy", Order.id(), Map::of);
    assertThatThrownBy(() -> EntityModels.resolve(List.of(Order, copy)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Entity models Order and Copy have the same id " + Order.id());
  }

  /// The models triggered on directly, in a reversal or in a transition chosen are run, after the ones given
  @Test
  public void modelsTriggeredOnAreAdded() {
    for (String how : List.of("trigger", "reversal", "choice", "choice in reversal")) {
      var process = process(Order, how);
      assertThat(EntityModels.resolve(List.of(process))).as(how).containsExactly(process, Order);
    }
  }

  /// The models of a group are those run, so a group triggered on adds none
  @Test
  public void groupTriggeredOnIsNotAdded() {
    var responder = EntityModel.of("Responder", UUID.randomUUID(), () -> Map.of(Begin, List.of(
        onEvent(Started).toSelf().trigger(Ordered).on(Orders).identifiedBy(entityIdFromSession()).output()
    )));
    assertThat(EntityModels.resolve(List.of(responder))).containsExactly(responder);
  }

  /// E.g. a trigger on an outbox request rather than on the copy of it its dispatcher has
  @Test
  public void triggerOnAnotherModelWithTheIdOfAModelGivenIsRejected() {
    var copy = EntityModel.of("Order", Order.id(), Order::transitions);
    assertThatThrownBy(() -> EntityModels.resolve(List.of(copy, process(Order, "trigger"))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Entity models Order and Order have the same id " + Order.id());
  }

}
