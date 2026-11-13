package com.github.thxmasj.backend.testapi;

import static com.github.thxmasj.backend.core.EntityModel.Begin;
import static com.github.thxmasj.backend.core.EntitySelector.CreationMode.CreateIfNotExists;
import static com.github.thxmasj.backend.core.EntitySelector.entityId;
import static com.github.thxmasj.backend.core.EntitySelector.newEntityId;
import static com.github.thxmasj.backend.core.EventTrigger.trigger;
import static com.github.thxmasj.backend.core.TransitionModelBuilder.WithEvent.onEvent;
import static com.github.thxmasj.backend.core.Tuples.tuple;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.thxmasj.backend.core.BasicEventType;
import com.github.thxmasj.backend.core.DataType;
import com.github.thxmasj.backend.core.EntityModel;
import com.github.thxmasj.backend.core.EntitySelector;
import com.github.thxmasj.backend.core.Event;
import com.github.thxmasj.backend.core.EventLog;
import com.github.thxmasj.backend.core.EventType;
import com.github.thxmasj.backend.core.GroupedDataType;
import com.github.thxmasj.backend.core.IdEntityModel;
import com.github.thxmasj.backend.core.IndexEntityModel;
import com.github.thxmasj.backend.core.State;
import com.github.thxmasj.backend.core.StateMachine;
import com.github.thxmasj.backend.core.TransitionModel;
import com.github.thxmasj.backend.core.Tuples.Tuple2;
import com.github.thxmasj.backend.core.test.Init;
import com.github.thxmasj.backend.testapi.TestApi.PendingConditionalTrigger;
import com.github.thxmasj.backend.testapi.TestApi.ScheduledEvent;
import java.nio.ByteBuffer;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

public class TestApiTest {

  enum AccountStates implements State {Opened, Closing, Closed, Expired}

  enum StatementStates implements State {Issued, Settled}

  record StatementNumber(String account, long number) {}

  /// Statement numbers grouped by account and ordered by number
  static class StatementNumberDataType implements GroupedDataType<StatementNumber, String> {
    @Override public String name() {return "StatementNumber";}
    @Override public byte[] marshalGroup(String account) {
      byte[] bytes = account.getBytes(UTF_8);
      return Arrays.copyOf(bytes, bytes.length + 1);
    }
    @Override public byte[] marshal(StatementNumber value) {
      byte[] group = marshalGroup(value.account());
      return ByteBuffer.allocate(group.length + Long.BYTES).put(group).putLong(value.number()).array();
    }
    @Override public StatementNumber unmarshal(byte[] value) {
      int accountLength = value.length - Long.BYTES - 1;
      return new StatementNumber(new String(value, 0, accountLength, UTF_8), ByteBuffer.wrap(value, accountLength + 1, Long.BYTES).getLong());
    }
  }

  static EventType<String, String> AccountNumberClaimed = BasicEventType.of("Account number claimed", UUID.fromString("5b0e8c3a-6f1d-4e2b-9a7c-1d3e5f7a9b01"), DataType.string());
  static IdEntityModel Ids = IdEntityModel.of("Ids", UUID.fromString("5b0e8c3a-6f1d-4e2b-9a7c-1d3e5f7a9b02"), AccountNumberClaimed);

  static EventType<UUID, UUID> StatementOfAccountReferenced = IndexEntityModel.referenceEventType("Statement of account referenced", UUID.fromString("5b0e8c3a-6f1d-4e2b-9a7c-1d3e5f7a9b03"));
  static EventType<StatementNumber, StatementNumber> NumberOfStatementIndexed = BasicEventType.of("Number of statement indexed", UUID.fromString("5b0e8c3a-6f1d-4e2b-9a7c-1d3e5f7a9b04"), new StatementNumberDataType());
  static IndexEntityModel Index = IndexEntityModel.of("Index", UUID.fromString("5b0e8c3a-6f1d-4e2b-9a7c-1d3e5f7a9b05"), StatementOfAccountReferenced, NumberOfStatementIndexed);

  static EntityModel Account = EntityModel.of("Account", UUID.fromString("5b0e8c3a-6f1d-4e2b-9a7c-1d3e5f7a9b06"));
  static EntityModel Statement = EntityModel.of("Statement", UUID.fromString("5b0e8c3a-6f1d-4e2b-9a7c-1d3e5f7a9b07"));

  static EventType<String, String> AccountOpened = BasicEventType.of("Account opened", UUID.fromString("5b0e8c3a-6f1d-4e2b-9a7c-1d3e5f7a9b08"), DataType.string());
  static EventType<Void, Void> AccountExpired = BasicEventType.of("Account expired", UUID.fromString("5b0e8c3a-6f1d-4e2b-9a7c-1d3e5f7a9b09"));
  static EventType<Void, Void> AccountCloseRequestedWhenSettled = BasicEventType.of("Account close requested when settled", UUID.fromString("5b0e8c3a-6f1d-4e2b-9a7c-1d3e5f7a9b0a"));
  static EventType<Void, Void> AccountClosed = BasicEventType.of("Account closed", UUID.fromString("5b0e8c3a-6f1d-4e2b-9a7c-1d3e5f7a9b0b"));

  record Issuing(UUID account, StatementNumber number) {}

  static EventType<Issuing, Issuing> StatementIssued = BasicEventType.of("Statement issued", UUID.fromString("5b0e8c3a-6f1d-4e2b-9a7c-1d3e5f7a9b0c"), DataType.json(Issuing.class));
  static EventType<Void, Void> StatementSettled = BasicEventType.of("Statement settled", UUID.fromString("5b0e8c3a-6f1d-4e2b-9a7c-1d3e5f7a9b0d"));

  enum ShipmentStates implements State {Sent}

  record Parcel(int weight) {}

  /// Shipments of letters and parcels share the id of their event type, like the outbox requests of different
  /// payloads, but differ in data type
  static UUID SendId = UUID.fromString("5b0e8c3a-6f1d-4e2b-9a7c-1d3e5f7a9b0e");
  static EventType<String, String> LetterSent = BasicEventType.of("Shipment sent", SendId, DataType.string());
  static EventType<Parcel, Parcel> ParcelSent = BasicEventType.of("Shipment sent", SendId, DataType.json(Parcel.class));
  static EventType<UUID, UUID> ShipmentOfAccountReferenced = IndexEntityModel.referenceEventType("Shipment of account referenced", UUID.fromString("5b0e8c3a-6f1d-4e2b-9a7c-1d3e5f7a9b0f"));
  static IndexEntityModel ShipmentIndex = IndexEntityModel.of("ShipmentIndex", UUID.fromString("5b0e8c3a-6f1d-4e2b-9a7c-1d3e5f7a9b10"), ShipmentOfAccountReferenced);
  static EntityModel LetterShipment = EntityModel.of("LetterShipment", UUID.fromString("5b0e8c3a-6f1d-4e2b-9a7c-1d3e5f7a9b11"));
  static EntityModel ParcelShipment = EntityModel.of("ParcelShipment", UUID.fromString("5b0e8c3a-6f1d-4e2b-9a7c-1d3e5f7a9b12"));

  static <T> Map<State, List<TransitionModel<?, ?>>> shipmentTransitions(EventType<T, T> send) {
    return Map.of(
        Begin, List.of(
            onEvent(send).to(ShipmentStates.Sent)
                .assemble(c -> tuple(c.input(), c.log().entityId()))
                .trigger(ShipmentOfAccountReferenced).with(_ -> shipmentAccount).on(ShipmentIndex).identifiedBy(d -> newEntityId(d.t2()))
                .output(d -> d.t1().t1())
        ),
        ShipmentStates.Sent, List.of()
    );
  }

  static final UUID shipmentAccount = UUID.randomUUID();

  static Map<EntityModel, Map<State, List<TransitionModel<?, ?>>>> transitions = Map.of(
      Account, Map.<State, List<TransitionModel<?, ?>>>of(
          Begin, List.of(
              onEvent(AccountOpened).to(AccountStates.Opened)
                  .assemble(c -> tuple(c.input(), c.log().entityId()))
                  .trigger(AccountNumberClaimed).with(Tuple2::t1).on(Ids).identifiedBy(d -> entityId(d.t2(), CreateIfNotExists))
                  .trigger(AccountExpired).after(_ -> Duration.ofHours(1))
                  .output(d -> d.t1().t1())
          ),
          AccountStates.Opened, List.of(
              onEvent(AccountExpired).to(AccountStates.Expired).output(),
              onEvent(AccountCloseRequestedWhenSettled).to(AccountStates.Closing)
                  .assemble(c -> c.log().entityId())
                  .trigger(AccountClosed)
                  .when(StatementOfAccountReferenced, id -> id)
                  .is(StatementSettled)
                  .with(_ -> null)
                  .on(Account)
                  .identifiedBy(EntitySelector::entityId)
                  .output()
          ),
          AccountStates.Closing, List.of(onEvent(AccountClosed).to(AccountStates.Closed).output()),
          AccountStates.Closed, List.of(),
          AccountStates.Expired, List.of()
      ),
      Statement, Map.<State, List<TransitionModel<?, ?>>>of(
          Begin, List.of(
              onEvent(StatementIssued).to(StatementStates.Issued)
                  .assemble(c -> tuple(c.input(), c.log().entityId()))
                  .trigger(StatementOfAccountReferenced).with(d -> d.t1().account()).on(Index).identifiedBy(d -> newEntityId(d.t2()))
                  .trigger(NumberOfStatementIndexed).with(d -> d.t1().t1().number()).on(Index).identifiedBy(d -> entityId(d.t1().t2()))
                  .output(d -> d.t1().t1().t1())
          ),
          StatementStates.Issued, List.of(onEvent(StatementSettled).to(StatementStates.Settled).output()),
          StatementStates.Settled, List.of()
      ),
      Ids, Ids.transitions(),
      Index, Index.transitions(),
      LetterShipment, shipmentTransitions(LetterSent),
      ParcelShipment, shipmentTransitions(ParcelSent),
      ShipmentIndex, ShipmentIndex.transitions()
  );

  private static final String schema = UUID.randomUUID().toString();
  private static final StateMachine machine = Init.stateMachine(schema, transitions);
  private static final TestApi api = new TestApi(Init.database(), Init.client(), schema, transitions, Clock.systemUTC());

  private static UUID open(String accountNumber) {
    return machine.onEvent(trigger(AccountOpened, Account), accountNumber).blockFirst().entityId();
  }

  private static UUID issue(UUID account, long number) {
    var issuing = new Issuing(account, new StatementNumber(account.toString(), number));
    return machine.onEvent(trigger(StatementIssued, Statement), issuing).blockFirst().entityId();
  }

  private static List<String> eventNames(EventLog log) {
    return log.events().stream().map(e -> e.eventNumber() + ":" + e.typeName()).toList();
  }

  private static EventLog awaitEvents(UUID entityId, int count) throws InterruptedException {
    long deadline = System.currentTimeMillis() + Duration.ofSeconds(10).toMillis();
    EventLog log = api.eventLog(entityId).block();
    while (log.events().size() < count && System.currentTimeMillis() < deadline) {
      Thread.sleep(200);
      log = api.eventLog(entityId).block();
    }
    return log;
  }

  @Test
  public void eventLogOfEntityHasTheEntityModelItWasCreatedWith() {
    UUID account = open("1001");

    EventLog log = api.eventLog(account).block();

    assertEquals(Account, log.entityModel());
    assertEquals(List.of("1:Account opened"), eventNames(log));
    assertEquals("1001", log.one(AccountOpened));
  }

  @Test
  public void eventLogOfUnknownEntityIsEmpty() {
    UUID unknown = UUID.randomUUID();

    assertNull(api.eventLog(unknown).block());
    assertNull(api.eventLog(Account, unknown).block());
  }

  @Test
  public void eventLogOfIdEntity() {
    UUID account = open("1002");

    assertEquals(List.of("1:Account number claimed"), eventNames(api.eventLog(Ids, account).block()));
  }

  @Test
  public void entityIsFoundByItsId() {
    UUID account = open("1003");

    assertEquals(account, api.eventLogById(AccountNumberClaimed, "1003").block().entityId());
    assertNull(api.eventLogById(AccountNumberClaimed, "unknown").block());
  }

  @Test
  public void idOfEntity() {
    UUID account = open("1004");

    assertEquals("1004", api.id(account, AccountNumberClaimed).block());
    assertNull(api.id(UUID.randomUUID(), AccountNumberClaimed).block());
  }

  @Test
  public void entitiesAreFoundByIndex() {
    UUID account = open("1005");
    UUID statement1 = issue(account, 1);
    UUID statement2 = issue(account, 2);

    List<UUID> statements = api.entitiesByIndex(StatementOfAccountReferenced, account)
        .map(EventLog::entityId)
        .collectList()
        .block();

    assertEquals(2, statements.size());
    assertTrue(statements.containsAll(List.of(statement1, statement2)));
  }

  @Test
  public void entitiesByIndexHaveTheEventTypesOfTheirEntityModel() {
    machine.onEvent(trigger(LetterSent, LetterShipment), "Dear account").blockFirst();
    machine.onEvent(trigger(ParcelSent, ParcelShipment), new Parcel(2)).blockFirst();

    Map<EntityModel, Object> sent = api.entitiesByIndex(ShipmentOfAccountReferenced, shipmentAccount)
        .collectMap(EventLog::entityModel, log -> (Object) log.events().getFirst().getUnmarshalledData())
        .block();

    assertEquals(Map.of(LetterShipment, "Dear account", ParcelShipment, new Parcel(2)), sent);
  }

  @Test
  public void lastEntityOfGroup() {
    UUID account = open("1006");
    issue(account, 1);
    UUID last = issue(account, 2);

    EventLog log = api.lastIndexed(Statement, NumberOfStatementIndexed, account.toString()).block();

    assertEquals(last, log.entityId());
    assertNull(api.lastIndexed(Statement, NumberOfStatementIndexed, "no such account").block());
  }

  @Test
  public void delayedTriggerOfEntity() {
    UUID account = open("1007");

    ScheduledEvent scheduled = api.delayedTrigger(account).block();

    assertEquals(Account, scheduled.entityModel());
    assertEquals(AccountExpired, scheduled.type());
    assertEquals(2, scheduled.eventNumber());
    assertEquals(0, scheduled.attempt());
    assertTrue(scheduled.deadline().isAfter(LocalDateTime.now(Clock.systemUTC()).plusMinutes(59)));
  }

  @Test
  public void expiredDelayedTriggerIsTriggered() throws InterruptedException {
    UUID account = open("1008");

    assertTrue(api.expireDelayedTrigger(account).block());

    assertEquals(List.of("1:Account opened", "2:Account expired"), eventNames(awaitEvents(account, 2)));
    assertNull(api.delayedTrigger(account).block());
    assertFalse(api.expireDelayedTrigger(account).block());
  }

  @Test
  public void delayedTriggerIsDeletedByNextEvent() throws SQLException {
    UUID account = open("1013");
    machine.onEvent(trigger(AccountCloseRequestedWhenSettled, Account, account)).blockFirst();

    try (var connection = Init.dataSource().getConnection();
         var statement = connection.prepareStatement("SELECT COUNT(*) FROM \"" + schema + "\".\"DelayedTrigger\" WHERE EntityId=?")) {
      statement.setObject(1, account);
      try (var rs = statement.executeQuery()) {
        rs.next();
        assertEquals(0, rs.getInt(1));
      }
    }
  }

  @Test
  public void deletedDelayedTriggersAreNotTriggered() {
    UUID account = open("1009");

    assertTrue(api.deleteDelayedTriggers().block() >= 1);

    assertNull(api.delayedTrigger(account).block());
  }

  @Test
  public void conditionalTriggersOfEntity() throws InterruptedException {
    UUID account = open("1010");
    UUID statement = issue(account, 1);
    machine.onEvent(trigger(AccountCloseRequestedWhenSettled, Account, account)).blockFirst();

    List<PendingConditionalTrigger> triggers = api.conditionalTriggers(account).collectList().block();

    assertEquals(1, triggers.size());
    assertEquals(AccountClosed, triggers.getFirst().type());
    assertEquals(List.of(StatementSettled), triggers.getFirst().lastTypes());
    assertEquals(StatementOfAccountReferenced, triggers.getFirst().referenceType());
    assertEquals(account, triggers.getFirst().triggerEntityId());
    assertEquals(2, triggers.getFirst().triggerEventNumber());

    machine.onEvent(trigger(StatementSettled, Statement, statement)).blockFirst();

    assertEquals(List.of("1:Account opened", "2:Account close requested when settled", "3:Account closed"), eventNames(awaitEvents(account, 3)));
    assertEquals(List.of(), api.conditionalTriggers(account).collectList().block());
  }

  @Test
  public void appendedEventPutsEntityInState() {
    UUID account = open("1011");

    Event<Void> appended = api.append(Account, account, AccountExpired, null).block();

    assertEquals(2, appended.eventNumber());
    assertEquals(List.of("1:Account opened", "2:Account expired"), eventNames(api.eventLog(account).block()));
    assertEquals(AccountStates.Expired, machine.onStatus(entityId(account), Account).block());
    // The event the entity was waiting for is cancelled, as if a transition had accepted the appended event
    assertNull(api.delayedTrigger(account).block());
  }

  @Test
  public void appendedEventCreatesEntity() {
    UUID account = UUID.randomUUID();

    api.append(Account, account, AccountOpened, "1012").block();
    api.append(Ids, account, AccountNumberClaimed, "1012").block();

    assertEquals(List.of("1:Account opened"), eventNames(api.eventLog(account).block()));
    assertEquals(account, api.eventLogById(AccountNumberClaimed, "1012").block().entityId());
  }

}
