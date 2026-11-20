package com.github.thxmasj.backend.core.test;

import static com.github.thxmasj.backend.core.EntityModel.Begin;
import static com.github.thxmasj.backend.core.EntitySelector.entityId;
import static com.github.thxmasj.backend.core.EntitySelector.newEntityId;
import static com.github.thxmasj.backend.core.EventTrigger.trigger;
import static com.github.thxmasj.backend.core.TransitionModelBuilder.WithEvent.onEvent;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.thxmasj.backend.core.BasicEventType;
import com.github.thxmasj.backend.core.DataType;
import com.github.thxmasj.backend.core.EntityModel;
import com.github.thxmasj.backend.core.Event;
import com.github.thxmasj.backend.core.EventLog;
import com.github.thxmasj.backend.core.EventType;
import com.github.thxmasj.backend.core.JsonLinesListener;
import com.github.thxmasj.backend.core.State;
import com.github.thxmasj.backend.core.StateMachine;
import com.github.thxmasj.backend.core.TransitionModel;
import com.github.thxmasj.backend.core.database.UnknownEntity;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/// Events and entities which are not stored, and events stored masked
@Tag("database")
public class NotStoredTest {

  enum States implements State { Opened, Viewed, Signed }

  static EventType<String, String> AccountOpened = BasicEventType.of("Account opened", UUID.fromString("5e0a3f8c-2b7d-4c19-9a64-d1f8b3e7a025"), DataType.string());
  static EventType<Void, Void> AccountTouched = BasicEventType.of("Account touched", UUID.fromString("c47d1e9b-0a3f-4b28-8e65-f2a9c1d7b304"));
  /// Not stored, and triggers a stored event on another entity
  static EventType<Void, String> PageViewed = BasicEventType.notStored("Page viewed", UUID.fromString("9b2c6e0f-4d1a-4e73-b8a5-3f7d9c2e1b46"), DataType.none(), DataType.string());
  static EventType<UUID, UUID> ViewRecorded = BasicEventType.of("View recorded", UUID.fromString("2f8a4d6c-9e1b-4a35-87c0-b5d3e9f1a627"), DataType.uuid());
  /// Not stored, creating an entity of the session only
  static EventType<UUID, UUID> SignInStarted = BasicEventType.notStored("Sign-in started", UUID.fromString("e1d5b9a3-7c2f-4086-9d4e-a6b8c0f2e315"), DataType.uuid());
  static EventType<UUID, String> SignedIn = BasicEventType.of("Signed in", UUID.fromString("7a3e9c1d-5b0f-4d62-a8e4-c9f1b3d5e708"), DataType.uuid(), DataType.string());
  static EventType<String, String> SecretIssued = BasicEventType.of("Secret issued", UUID.fromString("4c8f2a6e-1d9b-4357-b0e3-d7a5c9f1e842"), DataType.string());

  static EventType<String, String> PasswordSet = BasicEventType.of("Password set", UUID.fromString("6a1f3c5e-8b2d-4e90-a7c4-d9e1b3f5a768"), DataType.string());

  static EntityModel Account = EntityModel.of("Account", UUID.fromString("8d4b0f2a-6e3c-4a91-b7d5-e9c1f3a5b067"), NotStoredTest::accountTransitions);
  static EntityModel Views = EntityModel.of("Views", UUID.fromString("b6e2a8d4-0c5f-4e17-9a3b-f1d7c9e5a283"), NotStoredTest::viewTransitions);
  static EntityModel SignIn = EntityModel.of("SignIn", UUID.fromString("f3a7c1e5-9d2b-4f68-8c0a-b4e6d8f0a159"), NotStoredTest::signInTransitions);
  /// A model which is not stored
  static EntityModel Session = new EntityModel() {
    @Override public String name() {return "Session";}
    @Override public UUID id() {return UUID.fromString("0d6b2f8e-4a1c-4b73-95e7-c3a9f5d1b724");}
    @Override public Map<State, List<TransitionModel<?, ?>>> transitions() {return sessionTransitions();}
    @Override public boolean stored() {return false;}
  };

  private static final List<String> lines = new CopyOnWriteArrayList<>();
  private static final String schema = UUID.randomUUID().toString();
  private static final StateMachine machine = Init.stateMachine(
      schema,
      entityModels(),
      Clock.systemUTC(),
      new JsonLinesListener(lines::add, Clock.systemUTC())
  );

  private static List<EntityModel> entityModels() {
    return List.of(Account, Views, SignIn, Session);
  }

  private static Map<State, List<TransitionModel<?, ?>>> accountTransitions() {
    return Map.<State, List<TransitionModel<?, ?>>>of(
        Begin, List.of(onEvent(AccountOpened).to(States.Opened).assembleInput().output(d -> d)),
        States.Opened, List.of(
            onEvent(AccountTouched).toSelf().output(),
            onEvent(PasswordSet).toSelf().masked(_ -> "***").assembleInput().output(d -> d),
            onEvent(PageViewed).toSelf()
                .assemble(c -> c.log().entityId())
                .trigger(ViewRecorded).with(id -> id).on(Views).identifiedBy(newEntityId())
                .output(d -> "<html>" + d.t1() + "</html>")
        )
    );
  }

  private static Map<State, List<TransitionModel<?, ?>>> viewTransitions() {
    return Map.<State, List<TransitionModel<?, ?>>>of(
        Begin, List.of(onEvent(ViewRecorded).to(States.Viewed).assembleInput().output(d -> d)),
        States.Viewed, List.of()
    );
  }

  private static Map<State, List<TransitionModel<?, ?>>> signInTransitions() {
    return Map.<State, List<TransitionModel<?, ?>>>of(
        Begin, List.of(
            onEvent(SignInStarted).to(States.Opened)
                .assembleInput()
                .trigger(ViewRecorded).with(id -> id).on(Views).identifiedBy(newEntityId())
                // The view's entity id
                .output(d -> d.t2().accepted().event().entityId())
        ),
        States.Opened, List.of(onEvent(SignedIn).to(States.Signed).assembleInput().output(d -> "signed in " + d)),
        States.Signed, List.of()
    );
  }

  private static Map<State, List<TransitionModel<?, ?>>> sessionTransitions() {
    return Map.<State, List<TransitionModel<?, ?>>>of(
        Begin, List.of(
            onEvent(SecretIssued).to(States.Opened)
                .assembleInput()
                .trigger(AccountOpened).with(secret -> "account of " + secret).on(Account).identifiedBy(newEntityId())
                .output(d -> d.t1())
        ),
        States.Opened, List.of()
    );
  }

  private static EventLog log(EntityModel model, List<EventType<?, ?>> types, UUID entityId) {
    return Init.storage(schema, entityModels()).eventsByEntityId().execute(model, types, entityId).block();
  }

  private static List<String> events(EventLog log) {
    return log.events().stream().map(e -> e.eventNumber() + ":" + e.typeName()).toList();
  }

  @Test
  public void anEventNotStoredTriggersStoredEventsAndTheNextEventTakesItsNumber() {
    UUID account = machine.onEvent(trigger(AccountOpened, Account), "a").blockFirst().entityId();

    Event<?> viewed = machine.onEvent(trigger(PageViewed, Account, account), null)
        .filter(e -> e.type().equals(PageViewed))
        .blockFirst(Duration.ofSeconds(10));
    assertEquals("<html>" + account + "</html>", viewed.getUnmarshalledData());
    machine.onEvent(trigger(AccountTouched, Account, account), null).blockFirst();

    assertEquals(List.of("1:Account opened", "2:Account touched"), events(log(Account, List.of(AccountOpened, AccountTouched, PageViewed), account)));
    assertTrue(lines.stream().anyMatch(l -> l.contains("\"eventType\":\"Page viewed\"") && l.contains("\"stored\":false") && !l.contains("eventNumber")), lines.toString());
    assertTrue(lines.stream().anyMatch(l -> l.contains("\"eventType\":\"View recorded\"") && l.contains("\"eventNumber\":1")), lines.toString());
  }

  @Test
  public void anEntityCreatedByAnEventNotStoredIsOneOfTheSessionOnly() {
    UUID view = UUID.randomUUID();
    Event<?> started = machine.onEvent(trigger(SignInStarted, SignIn), view).blockFirst(Duration.ofSeconds(10));
    assertEquals(List.of("1:View recorded"), events(log(Views, List.of(ViewRecorded), (UUID) started.getUnmarshalledData())));
    var e = assertThrows(Exception.class, () -> log(SignIn, List.of(SignInStarted, SignedIn), started.entityId()));
    assertTrue(e instanceof UnknownEntity || e.getCause() instanceof UnknownEntity, e.toString());
    // After the session, the entity is not found
    assertThrows(Exception.class, () -> machine.onEvent(trigger(SignedIn, SignIn, started.entityId()), view).blockFirst(Duration.ofSeconds(10)));
  }

  @Test
  public void theEventsOfAModelNotStoredAreNotStoredButThoseTheyTriggerAre() {
    Event<?> issued = machine.onEvent(trigger(SecretIssued, Session), "s3cret").blockFirst(Duration.ofSeconds(10));
    assertEquals("s3cret", issued.getUnmarshalledData());

    var e = assertThrows(Exception.class, () -> log(Session, List.of(SecretIssued), issued.entityId()));
    assertTrue(e instanceof UnknownEntity || e.getCause() instanceof UnknownEntity, e.toString());
    assertTrue(lines.stream().anyMatch(l -> l.contains("\"eventType\":\"Secret issued\"") && l.contains("\"stored\":false")), lines.toString());
  }

  @Test
  public void aMaskedEventIsStoredMaskedAndHasItsWholeDataInTheSession() {
    UUID account = machine.onEvent(trigger(AccountOpened, Account), "a").blockFirst().entityId();

    Event<?> set = machine.onEvent(trigger(PasswordSet, Account, account), "hunter2").blockFirst(Duration.ofSeconds(10));
    assertEquals("hunter2", set.getUnmarshalledData());

    EventLog log = log(Account, List.of(AccountOpened, PasswordSet), account);
    assertEquals(List.of("1:Account opened", "2:Password set"), events(log));
    assertEquals("***", log.events().getLast().getUnmarshalledData());
  }
}
