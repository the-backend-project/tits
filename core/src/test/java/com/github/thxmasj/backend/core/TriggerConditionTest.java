package com.github.thxmasj.backend.core;

import static com.github.thxmasj.backend.core.EntitySelector.entityId;
import static com.github.thxmasj.backend.core.TransitionModelBuilder.WithEvent.onEvent;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

public class TriggerConditionTest {

  enum States implements State {Begin, Processing, Accepted}

  enum Entities implements EntityModel {
    Banana {
      @Override
      public UUID id() {
        return UUID.fromString("f01199a0-d2f0-4cc3-8c05-b44e0da7c3d1");
      }
    }
  }

  static EventType<Void, Void>
      BananaForwarded = BasicEventType.of("Banana forwarded", UUID.fromString("7f1a8a3e-4c52-4e0b-9b0e-2f6d1f1c2a01")),
      BananaRipened = BasicEventType.of("Banana ripened", UUID.fromString("7f1a8a3e-4c52-4e0b-9b0e-2f6d1f1c2a02")),
      BatchReferenced = BasicEventType.of("Batch referenced", UUID.fromString("7f1a8a3e-4c52-4e0b-9b0e-2f6d1f1c2a03")),
      BananaAccepted = BasicEventType.of("Banana accepted", UUID.fromString("7f1a8a3e-4c52-4e0b-9b0e-2f6d1f1c2a04"));

  @Test
  public void conditionIsStoredOnTrigger() {
    UUID indexKey = UUID.fromString("0b6c1f0e-3b8a-4f3e-9d7e-5a2b1c4d6e01");
    TransitionModel<?, ?> model = onEvent(BananaForwarded).to(States.Processing)
        .trigger(BananaRipened)
        .when(BatchReferenced, _ -> indexKey)
        .is(BananaAccepted)
        .with(_ -> null)
        .on(Entities.Banana)
        .identifiedBy(_ -> entityId(indexKey))
        .output();

    EventTrigger<?, ?, ?> trigger = model.triggers().getFirst();
    assertSame(BananaRipened, trigger.eventSpec().eventType());
    assertEquals(BatchReferenced, trigger.condition().referenceType());
    assertEquals(List.of(BananaAccepted), trigger.condition().lastTypes());
    assertEquals(indexKey, trigger.condition().indexKey().apply(null));
  }

  @Test
  public void triggerWithoutConditionHasNoCondition() {
    TransitionModel<?, ?> model = onEvent(BananaForwarded).to(States.Processing)
        .trigger(BananaRipened)
        .with(_ -> null)
        .on(Entities.Banana)
        .identifiedBy(_ -> entityId(UUID.randomUUID()))
        .output();

    assertNull(model.triggers().getFirst().condition());
  }

}
