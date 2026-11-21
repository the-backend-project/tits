package com.github.thxmasj.backend.core;

import static com.github.thxmasj.backend.core.TransitionModelBuilder.WithEvent.onEvent;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

public class MaskedEventTest {

  static EventType<String, String> PasswordSet = BasicEventType.of("Password set", UUID.fromString("1f3a5c7e-9b2d-4e46-a8c0-d2e4f6a8b013"), DataType.string());
  static EventType<Void, Void> Viewed = BasicEventType.notStored("Viewed", UUID.fromString("5b7d9f1a-3c4e-4a68-b0d2-e4f6a8c0d235"));
  static EventType<Void, Void> Touched = BasicEventType.of("Touched", UUID.fromString("9d1f3b5c-7e8a-4c80-a2b4-c6d8e0f2a457"));
  static EntityModel Account = EntityModel.of("Account", UUID.fromString("3c5e7a9b-1d2f-4b64-86a8-cadcebf0a679"), () -> Map.of());

  /// The transitions of the session read the log of the entity with the whole data of a masked event, by every accessor
  @Test
  public void theLogOfTheSessionHasTheWholeDataOfAMaskedEvent() {
    var event = new Event<>(UUID.randomUUID(), 1, PasswordSet, ZonedDateTime.now(), "hunter2", "***");
    var log = new EventLog(Account, event.entityId(), List.of(event));
    assertEquals("hunter2", log.one(PasswordSet));
    assertEquals("hunter2", log.oneIfExists(PasswordSet).orElseThrow());
    assertEquals("hunter2", log.one(DataType.string()));
    assertEquals("hunter2", log.number(1, PasswordSet));
    assertEquals("***", new String(event.getMarshalledData()));
  }

  @Test
  public void aTransitionOnAnEventNotStoredCantHaveAConditionalTrigger() {
    var e = assertThrows(IllegalArgumentException.class, () -> onEvent(Viewed).toSelf()
        .assemble(c -> c.log().entityId())
        .trigger(Touched).when(PasswordSet, id -> id).is(Touched).with(_ -> null).on(Account).identifiedBy(EntitySelector::entityId)
        .output());
    assertTrue(e.getMessage().startsWith("Viewed is not stored, so its transition can't have a conditional trigger"), e.getMessage());
  }

  /// A mask giving null stores no data, rather than the data unmasked
  @Test
  public void aMaskGivingNullStoresNoData() {
    var event = new Event<>(UUID.randomUUID(), 1, PasswordSet, ZonedDateTime.now(), "hunter2", null);
    assertEquals(null, event.getMarshalledData());
    assertEquals("hunter2", event.getUnmarshalledData());
    var built = onEvent(PasswordSet).toSelf().masked(_ -> null).assembleInput().output(d -> d);
    assertEquals(PasswordSet, built.eventType());
  }

  @Test
  public void anEventNotStoredCantBeADelayedEvent() {
    var e = assertThrows(IllegalArgumentException.class, () -> onEvent(Touched).toSelf()
        .assembleInput()
        .trigger(Viewed).after(_ -> java.time.Duration.ofMinutes(1))
        .output());
    assertTrue(e.getMessage().startsWith("Viewed is not stored, so it can't be a delayed event"), e.getMessage());
  }
}
