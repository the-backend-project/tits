package com.github.thxmasj.backend.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.thxmasj.backend.core.internal.TransitionStep;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EntityIdTest {

  @Test
  void newIdIsTimeOrdered() {
    EntityId id = EntityId.newId();
    assertEquals(7, id.value().version());
    assertNotEquals(id, EntityId.newId());
  }

  @Test
  void idOfTheEntityATransitionIsOn() {
    UUID entityId = Identifiers.newId();
    EventLog log = EventLog.empty(entityId, EntityModel.of("Test", UUID.randomUUID()));
    var context = new TransitionStep.InputStep<>(null, null, null, null, null, 1, log, null, null, null, null, null);
    assertEquals(EntityId.of(entityId), context.entityId());
  }

  @Test
  void canNotBeMadeFromAUuidOutsideTheLibrary() {
    assertTrue(Arrays.stream(EntityId.class.getDeclaredConstructors()).allMatch(c -> Modifier.isPrivate(c.getModifiers())));
    assertTrue(Arrays.stream(EntityId.class.getDeclaredMethods())
        .filter(m -> Modifier.isPublic(m.getModifiers()) && Modifier.isStatic(m.getModifiers()))
        .allMatch(m -> m.getParameterCount() == 0));
  }

}
