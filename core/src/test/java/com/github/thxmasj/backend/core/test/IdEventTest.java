package com.github.thxmasj.backend.core.test;

import static com.github.thxmasj.backend.core.EntityModel.Begin;
import static com.github.thxmasj.backend.core.EntitySelector.lastIndexed;
import static com.github.thxmasj.backend.core.EntitySelector.CreationMode.CreateIfNotExists;
import static com.github.thxmasj.backend.core.EntitySelector.entityId;
import static com.github.thxmasj.backend.core.EntitySelector.newEntityId;
import static com.github.thxmasj.backend.core.EntitySelector.secondToLastIndexed;
import static com.github.thxmasj.backend.core.EventTrigger.trigger;
import static com.github.thxmasj.backend.core.TransitionModelBuilder.WithEvent.onEvent;
import static com.github.thxmasj.backend.core.Tuples.tuple;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.github.thxmasj.backend.core.BasicEventType;
import com.github.thxmasj.backend.core.BasicEventType.ReadOnly;
import com.github.thxmasj.backend.core.DataType;
import com.github.thxmasj.backend.core.EntityModel;
import com.github.thxmasj.backend.core.EntitySelector;
import com.github.thxmasj.backend.core.Event;
import com.github.thxmasj.backend.core.EventLog;
import com.github.thxmasj.backend.core.EventTrigger;
import com.github.thxmasj.backend.core.EventTrigger.EventSpec;
import com.github.thxmasj.backend.core.EventType;
import com.github.thxmasj.backend.core.GroupedDataType;
import com.github.thxmasj.backend.core.IdEntityModel;
import com.github.thxmasj.backend.core.IndexEntityModel;
import com.github.thxmasj.backend.core.State;
import com.github.thxmasj.backend.core.StateMachine;
import com.github.thxmasj.backend.core.StateMachine.RejectedEvent;
import com.github.thxmasj.backend.core.StateMachine.RejectedEvent.Reason;
import com.github.thxmasj.backend.core.TransitionModel;
import com.github.thxmasj.backend.core.Tuples.Tuple2;
import java.nio.ByteBuffer;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("database")
public class IdEventTest {

  enum States implements State { Opened, Issued }

  static EventType<String, String> AccountNumberClaimed = BasicEventType.of("Account number claimed", UUID.fromString("0c3f6a0e-2b51-4d55-9f43-a7a1d7f86d2b"), DataType.string());
  static EventType<String, String> AliasClaimed = BasicEventType.of("Alias claimed", UUID.fromString("6e2a9c4d-8b1f-4d3a-a5e7-2c9b0f4d6a18"), DataType.string());

  static IdEntityModel Ids = IdEntityModel.of("Ids", UUID.fromString("9a7c5e3b-1d2f-4a6b-8c0e-4f6a8b0c2d4e"), AccountNumberClaimed, AliasClaimed);

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

  static EventType<StatementNumber, StatementNumber> StatementOfAccountIndexed = BasicEventType.of("Statement of account indexed", UUID.fromString("b9f6e6a4-7a0d-4c1f-a5a9-1f7b0c8e3d42"), new StatementNumberDataType());
  static IndexEntityModel Index = IndexEntityModel.of("Index", UUID.fromString("2d4f6a8c-0e1b-4c3d-9e5f-7a9b1c3d5e7f"), StatementOfAccountIndexed);

  static EntityModel Account = EntityModel.of("Account", UUID.fromString("5d1b3c86-1b0e-4f7e-8a8c-2d6c9f0b7e15"));
  static EntityModel Statement = EntityModel.of("Statement", UUID.fromString("e4a7d2f9-6c3b-4e8a-9b1d-7f5c0a2e8d36"));

  static EventType<String, String> AccountOpened = BasicEventType.of("Account opened", UUID.fromString("a2d4f6b8-1c3e-4a5b-8d7f-9e0a1b2c3d4e"), DataType.string());
  static EventType<Void, Void> AccountTouched = BasicEventType.of("Account touched", UUID.fromString("f1e2d3c4-b5a6-4978-8a6b-5c4d3e2f1a0b"));
  static EventType<StatementNumber, StatementNumber> StatementIssued = BasicEventType.of("Statement issued", UUID.fromString("3b5d7f9a-2c4e-4a6b-8c0d-1e2f3a4b5c6d"), DataType.json(StatementNumber.class));
  static EventType<Void, String> StatementLookedUp = new ReadOnly<>("Statement looked up", UUID.fromString("7c9e1a3b-5d7f-4b9a-8c1e-3f5a7b9c1d2e"), DataType.none(), DataType.string());

  private static final String schema = UUID.randomUUID().toString();
  private static final StateMachine machine = stateMachine(schema, Clock.systemUTC());

  private static StateMachine stateMachine(String schema, Clock clock) {
    return Init.stateMachine(schema, transitions(), clock);
  }

  private static Map<EntityModel, Map<State, List<TransitionModel<?, ?>>>> transitions() {
    return Map.of(
      Account, Map.<State, List<TransitionModel<?, ?>>>of(
          Begin, List.of(
              onEvent(AccountOpened).to(States.Opened)
                  .assemble(c -> tuple(c.input(), c.log().entityId()))
                  .trigger(AccountNumberClaimed).with(Tuple2::t1).on(Ids).identifiedBy(d -> entityId(d.t2(), CreateIfNotExists))
                  .trigger(AliasClaimed).with(d -> "alias-" + d.t1().t1()).on(Ids).identifiedBy(d -> entityId(d.t1().t2(), CreateIfNotExists))
                  .output(d -> d.t1().t2().isIdExists() ?
                      "Duplicate of " + d.t1().t2().idExists().log().entityId() :
                      d.t1().t1().t1())
          ),
          States.Opened, List.of(onEvent(AccountTouched).toSelf().output())
      ),
      Statement, Map.<State, List<TransitionModel<?, ?>>>of(
          Begin, List.of(
              onEvent(StatementIssued).to(States.Issued)
                  .assemble(c -> tuple(c.input(), c.log().entityId()))
                  .trigger(StatementOfAccountIndexed).with(Tuple2::t1).on(Index).identifiedBy(d -> newEntityId(d.t2()))
                  .output(d -> d.t1().t1())
          ),
          States.Issued, List.of(onEvent(StatementLookedUp).toSelf().assemble(c -> c.log().entityId().toString()).output(d -> d))
      ),
      Ids, Ids.transitions(),
      Index, Index.transitions()
    );
  }

  @Test
  public void idsAreEventsOfASeparateIdEntity() {
    Event<?> opened = machine.onEvent(trigger(AccountOpened, Account), "123").blockFirst();

    var eventsByEntityId = Init.storage(schema, transitions()).eventsByEntityId();
    EventLog accountLog = eventsByEntityId.execute(Account, List.of(AccountOpened, AccountTouched), opened.entityId()).block();
    EventLog idLog = eventsByEntityId.execute(Ids, List.of(AccountNumberClaimed, AliasClaimed), opened.entityId()).block();

    assertEquals(List.of("1:Account opened"), accountLog.events().stream().map(e -> e.eventNumber() + ":" + e.typeName()).toList());
    assertEquals(List.of("1:Account number claimed", "2:Alias claimed"), idLog.events().stream().map(e -> e.eventNumber() + ":" + e.typeName()).toList());
    assertEquals("alias-123", idLog.one(AliasClaimed));
  }

  @Test
  public void entityIsFoundByItsId() {
    Event<?> opened = machine.onEvent(trigger(AccountOpened, Account), "456").blockFirst();

    Event<?> touched = machine.onEvent(trigger(AccountTouched, Account, AliasClaimed, "alias-456")).blockFirst();

    assertEquals(opened.entityId(), touched.entityId());
    assertEquals(2, touched.eventNumber());
  }

  @Test
  public void eventIsRejectedWhenNoEntityHasTheId() {
    RejectedEvent rejected = assertThrows(
        RejectedEvent.class,
        () -> machine.onEvent(trigger(AccountTouched, Account, AliasClaimed, "alias-unknown")).blockFirst()
    );

    assertEquals(Reason.UnknownEntity, rejected.reason());
    assertEquals(Account, rejected.entityModel());
    assertNull(rejected.entityId());
  }

  @Test
  public void existingIdIsTheResultOfTriggeringIt() {
    Event<?> first = machine.onEvent(trigger(AccountOpened, Account), "789").blockFirst();

    Event<?> second = machine.onEvent(trigger(AccountOpened, Account), "789").blockFirst();

    assertEquals("Duplicate of " + first.entityId(), second.getUnmarshalledData());
    // Neither of the existing ids is stored again
    var eventsByEntityId = Init.storage(schema, transitions()).eventsByEntityId();
    assertEquals(
        List.of(),
        eventsByEntityId.execute(Ids, List.of(AccountNumberClaimed, AliasClaimed), second.entityId())
            .map(EventLog::events)
            .onErrorReturn(List.of())
            .block()
    );
  }

  @Test
  public void entityIsFoundByTheLastIndexEventsOfAGroup() {
    List<UUID> issued = new ArrayList<>();
    for (var number : List.of(new StatementNumber("A", 1), new StatementNumber("A", 2), new StatementNumber("B", 1), new StatementNumber("A", 3)))
      issued.add(machine.onEvent(trigger(StatementIssued, Statement), number).blockFirst().entityId());

    assertEquals(issued.get(3).toString(), read(lastIndexed(StatementOfAccountIndexed, "A")));
    assertEquals(issued.get(1).toString(), read(secondToLastIndexed(StatementOfAccountIndexed, "A")));
    assertEquals(issued.get(2).toString(), read(lastIndexed(StatementOfAccountIndexed, "B")));
    // The only one is both the last and the second to last
    assertEquals(issued.get(2).toString(), read(secondToLastIndexed(StatementOfAccountIndexed, "B")));
  }

  @Test
  public void groupIsOrderedByDataNotByTimeOfIndexing() {
    StateMachine frozen = stateMachine(UUID.randomUUID().toString(), Clock.fixed(Instant.parse("2026-11-11T12:00:00Z"), ZoneOffset.UTC));
    List<UUID> issued = new ArrayList<>();
    for (long number : List.of(10L, 9L, 2L))
      issued.add(frozen.onEvent(trigger(StatementIssued, Statement), new StatementNumber("C", number)).blockFirst().entityId());

    assertEquals(issued.get(0).toString(), read(frozen, lastIndexed(StatementOfAccountIndexed, "C")));
    assertEquals(issued.get(1).toString(), read(frozen, secondToLastIndexed(StatementOfAccountIndexed, "C")));
  }

  private String read(EntitySelector selector) {
    return read(machine, selector);
  }

  private static String read(StateMachine machine, EntitySelector selector) {
    return (String) machine.onEvent(new EventTrigger<Void, Void, String>(new EventSpec<>(StatementLookedUp, d -> d), List.of(_ -> selector), Statement, false))
        .blockFirst()
        .getUnmarshalledData();
  }

}
