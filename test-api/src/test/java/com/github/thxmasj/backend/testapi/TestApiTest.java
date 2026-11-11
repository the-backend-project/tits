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
import com.github.thxmasj.backend.core.EntityId;
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
import com.github.thxmasj.backend.core.TransitionModelBuilder.TransitionModel;
import com.github.thxmasj.backend.core.Tuples.Tuple2;
import com.github.thxmasj.backend.core.test.Init;
import com.github.thxmasj.backend.testapi.TestApi.PendingConditionTrigger;
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

  static EventType<String, String> AccountNumber = BasicEventType.of("AccountNumber", UUID.fromString("5b0e8c3a-6f1d-4e2b-9a7c-1d3e5f7a9b01"), DataType.string());
  static IdEntityModel Ids = IdEntityModel.of("Ids", UUID.fromString("5b0e8c3a-6f1d-4e2b-9a7c-1d3e5f7a9b02"), AccountNumber);

  static EventType<UUID, UUID> StatementOfAccount = IndexEntityModel.referenceEventType("StatementOfAccount", UUID.fromString("5b0e8c3a-6f1d-4e2b-9a7c-1d3e5f7a9b03"));
  static EventType<StatementNumber, StatementNumber> NumberOfStatement = BasicEventType.of("NumberOfStatement", UUID.fromString("5b0e8c3a-6f1d-4e2b-9a7c-1d3e5f7a9b04"), new StatementNumberDataType());
  static IndexEntityModel Index = IndexEntityModel.of("Index", UUID.fromString("5b0e8c3a-6f1d-4e2b-9a7c-1d3e5f7a9b05"), StatementOfAccount, NumberOfStatement);

  static EntityModel Account = EntityModel.of("Account", UUID.fromString("5b0e8c3a-6f1d-4e2b-9a7c-1d3e5f7a9b06"));
  static EntityModel Statement = EntityModel.of("Statement", UUID.fromString("5b0e8c3a-6f1d-4e2b-9a7c-1d3e5f7a9b07"));

  static EventType<String, String> Open = BasicEventType.of("Open", UUID.fromString("5b0e8c3a-6f1d-4e2b-9a7c-1d3e5f7a9b08"), DataType.string());
  static EventType<Void, Void> Expire = BasicEventType.of("Expire", UUID.fromString("5b0e8c3a-6f1d-4e2b-9a7c-1d3e5f7a9b09"));
  static EventType<Void, Void> CloseWhenSettled = BasicEventType.of("CloseWhenSettled", UUID.fromString("5b0e8c3a-6f1d-4e2b-9a7c-1d3e5f7a9b0a"));
  static EventType<Void, Void> Close = BasicEventType.of("Close", UUID.fromString("5b0e8c3a-6f1d-4e2b-9a7c-1d3e5f7a9b0b"));

  record Issuing(UUID account, StatementNumber number) {}

  static EventType<Issuing, Issuing> Issue = BasicEventType.of("Issue", UUID.fromString("5b0e8c3a-6f1d-4e2b-9a7c-1d3e5f7a9b0c"), DataType.json(Issuing.class));
  static EventType<Void, Void> Settle = BasicEventType.of("Settle", UUID.fromString("5b0e8c3a-6f1d-4e2b-9a7c-1d3e5f7a9b0d"));

  static Map<EntityModel, Map<State, List<TransitionModel<?, ?>>>> transitions = Map.of(
      Account, Map.<State, List<TransitionModel<?, ?>>>of(
          Begin, List.of(
              onEvent(Open).to(AccountStates.Opened)
                  .assemble(c -> tuple(c.input(), c.log().entityId()))
                  .trigger(AccountNumber).with(Tuple2::t1).on(Ids).identifiedBy(d -> entityId(d.t2(), CreateIfNotExists))
                  .trigger(Expire).after(_ -> Duration.ofHours(1))
                  .output(d -> d.t1().t1())
          ),
          AccountStates.Opened, List.of(
              onEvent(Expire).to(AccountStates.Expired).output(),
              onEvent(CloseWhenSettled).to(AccountStates.Closing)
                  .assemble(c -> c.log().entityId())
                  .trigger(Close)
                  .when(StatementOfAccount, id -> id.value())
                  .is(Settle)
                  .with(_ -> null)
                  .on(Account)
                  .identifiedBy(EntitySelector::entityId)
                  .output()
          ),
          AccountStates.Closing, List.of(onEvent(Close).to(AccountStates.Closed).output()),
          AccountStates.Closed, List.of(),
          AccountStates.Expired, List.of()
      ),
      Statement, Map.<State, List<TransitionModel<?, ?>>>of(
          Begin, List.of(
              onEvent(Issue).to(StatementStates.Issued)
                  .assemble(c -> tuple(c.input(), c.log().entityId()))
                  .trigger(StatementOfAccount).with(d -> d.t1().account()).on(Index).identifiedBy(d -> newEntityId(d.t2().value()))
                  .trigger(NumberOfStatement).with(d -> d.t1().t1().number()).on(Index).identifiedBy(d -> entityId(d.t1().t2().value()))
                  .output(d -> d.t1().t1().t1())
          ),
          StatementStates.Issued, List.of(onEvent(Settle).to(StatementStates.Settled).output()),
          StatementStates.Settled, List.of()
      ),
      Ids, Ids.transitions(),
      Index, Index.transitions()
  );

  private static final String schema = UUID.randomUUID().toString();
  private static final StateMachine machine = Init.stateMachine(schema, transitions);
  private static final TestApi api = new TestApi(Init.dataSource(), schema, transitions, Clock.systemUTC());

  private static EntityId open(String accountNumber) {
    return new EntityId.UUID(machine.onEvent(trigger(Open, Account), accountNumber).blockFirst().entityId());
  }

  private static EntityId issue(EntityId account, long number) {
    var issuing = new Issuing(account.value(), new StatementNumber(account.value().toString(), number));
    return new EntityId.UUID(machine.onEvent(trigger(Issue, Statement), issuing).blockFirst().entityId());
  }

  private static List<String> eventNames(EventLog log) {
    return log.events().stream().map(e -> e.eventNumber() + ":" + e.typeName()).toList();
  }

  private static EventLog awaitEvents(EntityId entityId, int count) throws InterruptedException {
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
    EntityId account = open("1001");

    EventLog log = api.eventLog(account).block();

    assertEquals(Account, log.entityModel());
    assertEquals(List.of("1:Open"), eventNames(log));
    assertEquals("1001", log.one(Open));
  }

  @Test
  public void eventLogOfUnknownEntityIsEmpty() {
    EntityId unknown = new EntityId.UUID(UUID.randomUUID());

    assertNull(api.eventLog(unknown).block());
    assertNull(api.eventLog(Account, unknown).block());
  }

  @Test
  public void eventLogOfIdEntity() {
    EntityId account = open("1002");

    assertEquals(List.of("1:AccountNumber"), eventNames(api.eventLog(Ids, account).block()));
  }

  @Test
  public void entityIsFoundByItsId() {
    EntityId account = open("1003");

    assertEquals(account, api.eventLogById(AccountNumber, "1003").block().entityId());
    assertNull(api.eventLogById(AccountNumber, "unknown").block());
  }

  @Test
  public void idOfEntity() {
    EntityId account = open("1004");

    assertEquals("1004", api.id(account, AccountNumber).block());
    assertNull(api.id(new EntityId.UUID(UUID.randomUUID()), AccountNumber).block());
  }

  @Test
  public void entitiesAreFoundByIndex() {
    EntityId account = open("1005");
    EntityId statement1 = issue(account, 1);
    EntityId statement2 = issue(account, 2);

    List<EntityId> statements = api.entitiesByIndex(StatementOfAccount, account.value())
        .map(EventLog::entityId)
        .collectList()
        .block();

    assertEquals(2, statements.size());
    assertTrue(statements.containsAll(List.of(statement1, statement2)));
  }

  @Test
  public void lastEntityOfGroup() {
    EntityId account = open("1006");
    issue(account, 1);
    EntityId last = issue(account, 2);

    EventLog log = api.lastIndexed(Statement, NumberOfStatement, account.value().toString()).block();

    assertEquals(last, log.entityId());
    assertNull(api.lastIndexed(Statement, NumberOfStatement, "no such account").block());
  }

  @Test
  public void delayedTriggerOfEntity() {
    EntityId account = open("1007");

    ScheduledEvent scheduled = api.delayedTrigger(account).block();

    assertEquals(Account, scheduled.entityModel());
    assertEquals(Expire, scheduled.type());
    assertEquals(2, scheduled.eventNumber());
    assertEquals(0, scheduled.attempt());
    assertTrue(scheduled.deadline().isAfter(LocalDateTime.now(Clock.systemUTC()).plusMinutes(59)));
  }

  @Test
  public void expiredDelayedTriggerIsTriggered() throws InterruptedException {
    EntityId account = open("1008");

    assertTrue(api.expireDelayedTrigger(account).block());

    assertEquals(List.of("1:Open", "2:Expire"), eventNames(awaitEvents(account, 2)));
    assertNull(api.delayedTrigger(account).block());
    assertFalse(api.expireDelayedTrigger(account).block());
  }

  @Test
  public void delayedTriggerIsDeletedByNextEvent() throws SQLException {
    EntityId account = open("1013");
    machine.onEvent(trigger(CloseWhenSettled, Account, account.value())).blockFirst();

    try (var connection = Init.dataSource().getConnection();
         var statement = connection.prepareStatement("SELECT COUNT(*) FROM [" + schema + "].[DelayedTrigger] WHERE EntityId=?")) {
      statement.setObject(1, account.value());
      try (var rs = statement.executeQuery()) {
        rs.next();
        assertEquals(0, rs.getInt(1));
      }
    }
  }

  @Test
  public void deletedDelayedTriggersAreNotTriggered() {
    EntityId account = open("1009");

    assertTrue(api.deleteDelayedTriggers().block() >= 1);

    assertNull(api.delayedTrigger(account).block());
  }

  @Test
  public void conditionTriggersOfEntity() throws InterruptedException {
    EntityId account = open("1010");
    EntityId statement = issue(account, 1);
    machine.onEvent(trigger(CloseWhenSettled, Account, account.value())).blockFirst();

    List<PendingConditionTrigger> triggers = api.conditionTriggers(account).collectList().block();

    assertEquals(1, triggers.size());
    assertEquals(Close, triggers.getFirst().type());
    assertEquals(List.of(Settle), triggers.getFirst().lastTypes());
    assertEquals(StatementOfAccount, triggers.getFirst().referenceType());
    assertEquals(account, triggers.getFirst().triggerEntityId());
    assertEquals(2, triggers.getFirst().triggerEventNumber());

    machine.onEvent(trigger(Settle, Statement, statement.value())).blockFirst();

    assertEquals(List.of("1:Open", "2:CloseWhenSettled", "3:Close"), eventNames(awaitEvents(account, 3)));
    assertEquals(List.of(), api.conditionTriggers(account).collectList().block());
  }

  @Test
  public void appendedEventPutsEntityInState() {
    EntityId account = open("1011");

    Event<Void> appended = api.append(Account, account, Expire, null).block();

    assertEquals(2, appended.eventNumber());
    assertEquals(List.of("1:Open", "2:Expire"), eventNames(api.eventLog(account).block()));
    assertEquals(AccountStates.Expired, machine.onStatus(entityId(account), Account).block());
    // The event the entity was waiting for is cancelled, as if a transition had accepted the appended event
    assertNull(api.delayedTrigger(account).block());
  }

  @Test
  public void appendedEventCreatesEntity() {
    EntityId account = new EntityId.UUID(UUID.randomUUID());

    api.append(Account, account, Open, "1012").block();
    api.append(Ids, account, AccountNumber, "1012").block();

    assertEquals(List.of("1:Open"), eventNames(api.eventLog(account).block()));
    assertEquals(account, api.eventLogById(AccountNumber, "1012").block().entityId());
  }

}
