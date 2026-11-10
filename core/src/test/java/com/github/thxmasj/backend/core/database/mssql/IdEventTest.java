package com.github.thxmasj.backend.core.database.mssql;

import static com.github.thxmasj.backend.core.EntityModel.Begin;
import static com.github.thxmasj.backend.core.EntitySelector.lastIndexed;
import static com.github.thxmasj.backend.core.EntitySelector.CreationMode.CreateIfNotExists;
import static com.github.thxmasj.backend.core.EntitySelector.entityId;
import static com.github.thxmasj.backend.core.EntitySelector.newEntityId;
import static com.github.thxmasj.backend.core.EntitySelector.secondToLastIndexed;
import static com.github.thxmasj.backend.core.EventTrigger.trigger;
import static com.github.thxmasj.backend.core.TransitionModelBuilder.WithEvent.onEvent;
import static com.github.thxmasj.backend.core.Tuples.tuple;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.github.thxmasj.backend.core.BasicEventType;
import com.github.thxmasj.backend.core.BasicEventType.ReadOnly;
import com.github.thxmasj.backend.core.DataType;
import com.github.thxmasj.backend.core.EntityId;
import com.github.thxmasj.backend.core.EntityModel;
import com.github.thxmasj.backend.core.EntitySelector;
import com.github.thxmasj.backend.core.Event;
import com.github.thxmasj.backend.core.EventLog;
import com.github.thxmasj.backend.core.EventTrigger;
import com.github.thxmasj.backend.core.EventTrigger.EventSpec;
import com.github.thxmasj.backend.core.EventType;
import com.github.thxmasj.backend.core.IdEntityModel;
import com.github.thxmasj.backend.core.IndexEntityModel;
import com.github.thxmasj.backend.core.State;
import com.github.thxmasj.backend.core.StateMachine;
import com.github.thxmasj.backend.core.TransitionModelBuilder.TransitionModel;
import com.github.thxmasj.backend.core.Tuples.Tuple2;
import com.github.thxmasj.backend.core.test.Init;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

public class IdEventTest {

  enum States implements State { Opened, Issued }

  static EventType<String, String> AccountNumber = BasicEventType.of("AccountNumber", UUID.fromString("0c3f6a0e-2b51-4d55-9f43-a7a1d7f86d2b"), DataType.string());
  static EventType<String, String> Alias = BasicEventType.of("Alias", UUID.fromString("6e2a9c4d-8b1f-4d3a-a5e7-2c9b0f4d6a18"), DataType.string());

  static IdEntityModel Ids = IdEntityModel.of("Ids", UUID.fromString("9a7c5e3b-1d2f-4a6b-8c0e-4f6a8b0c2d4e"), AccountNumber, Alias);

  static EventType<String, String> StatementOfAccount = BasicEventType.of("StatementOfAccount", UUID.fromString("b9f6e6a4-7a0d-4c1f-a5a9-1f7b0c8e3d42"), DataType.string());
  static IndexEntityModel Index = IndexEntityModel.of("Index", UUID.fromString("2d4f6a8c-0e1b-4c3d-9e5f-7a9b1c3d5e7f"), StatementOfAccount);

  static EntityModel Account = EntityModel.of("Account", UUID.fromString("5d1b3c86-1b0e-4f7e-8a8c-2d6c9f0b7e15"));
  static EntityModel Statement = EntityModel.of("Statement", UUID.fromString("e4a7d2f9-6c3b-4e8a-9b1d-7f5c0a2e8d36"));

  static EventType<String, String> Open = BasicEventType.of("Open", UUID.fromString("a2d4f6b8-1c3e-4a5b-8d7f-9e0a1b2c3d4e"), DataType.string());
  static EventType<Void, Void> Touch = BasicEventType.of("Touch", UUID.fromString("f1e2d3c4-b5a6-4978-8a6b-5c4d3e2f1a0b"));
  static EventType<String, String> Issue = BasicEventType.of("Issue", UUID.fromString("3b5d7f9a-2c4e-4a6b-8c0d-1e2f3a4b5c6d"), DataType.string());
  static EventType<Void, String> Read = new ReadOnly<>("Read", UUID.fromString("7c9e1a3b-5d7f-4b9a-8c1e-3f5a7b9c1d2e"), DataType.none(), DataType.string());

  private static final String schema = UUID.randomUUID().toString();
  private static final StateMachine machine = Init.stateMachine(schema, Map.of(
      Account, Map.<State, List<TransitionModel<?, ?>>>of(
          Begin, List.of(
              onEvent(Open).to(States.Opened)
                  .assemble(c -> tuple(c.input(), c.log().entityId()))
                  .trigger(AccountNumber).with(Tuple2::t1).on(Ids).identifiedBy(d -> entityId(d.t2(), CreateIfNotExists))
                  .trigger(Alias).with(d -> "alias-" + d.t1().t1()).on(Ids).identifiedBy(d -> entityId(d.t1().t2(), CreateIfNotExists))
                  .output(d -> {
                    // Handles the rejection of both ids, as an account number and its alias are taken together
                    boolean taken = d.t1().t2().isRejected() & d.t2().isRejected();
                    return taken ? "Duplicate of " + d.t1().t2().rejected().idOwner().entityId().value() : d.t1().t1().t1();
                  })
          ),
          States.Opened, List.of(onEvent(Touch).toSelf().output())
      ),
      Statement, Map.<State, List<TransitionModel<?, ?>>>of(
          Begin, List.of(
              onEvent(Issue).to(States.Issued)
                  .assemble(c -> tuple(c.input(), c.log().entityId()))
                  .trigger(StatementOfAccount).with(Tuple2::t1).on(Index).identifiedBy(d -> newEntityId(d.t2().value()))
                  .output(d -> d.t1().t1())
          ),
          States.Issued, List.of(onEvent(Read).toSelf().assemble(c -> c.log().entityId().value().toString()).output(d -> d))
      ),
      Ids, Ids.transitions(),
      Index, Index.transitions()
  ));

  @Test
  public void idsAreEventsOfASeparateIdEntity() {
    Event<?> opened = machine.onEvent(trigger(Open, Account), "123").blockFirst();

    var eventsByEntityId = new EventsByEntityId(Init.dataSource(), List.of(Account, Ids), schema, Clock.systemUTC());
    EventLog accountLog = eventsByEntityId.execute(Account, List.of(Open, Touch), new EntityId.UUID(opened.entityId())).block();
    EventLog idLog = eventsByEntityId.execute(Ids, List.of(AccountNumber, Alias), new EntityId.UUID(opened.entityId())).block();

    assertEquals(List.of("1:Open"), accountLog.events().stream().map(e -> e.eventNumber() + ":" + e.typeName()).toList());
    assertEquals(List.of("1:AccountNumber", "2:Alias"), idLog.events().stream().map(e -> e.eventNumber() + ":" + e.typeName()).toList());
    assertEquals("alias-123", idLog.one(Alias));
  }

  @Test
  public void entityIsFoundByItsId() {
    Event<?> opened = machine.onEvent(trigger(Open, Account), "456").blockFirst();

    Event<?> touched = machine.onEvent(trigger(Touch, Account, Alias, "alias-456")).blockFirst();

    assertEquals(opened.entityId(), touched.entityId());
    assertEquals(2, touched.eventNumber());
  }

  @Test
  public void takenIdIsRejectedWithTheLogOfTheEntityHavingIt() {
    Event<?> first = machine.onEvent(trigger(Open, Account), "789").blockFirst();

    Event<?> second = machine.onEvent(trigger(Open, Account), "789").blockFirst();

    assertEquals("Duplicate of " + first.entityId(), second.getUnmarshalledData());
  }

  @Test
  public void entityIsFoundByTheLastIndexEventsOfAKey() {
    List<UUID> issued = new ArrayList<>();
    for (String account : List.of("A", "A", "B", "A"))
      issued.add(machine.onEvent(trigger(Issue, Statement), account).blockFirst().entityId());

    assertEquals(issued.get(3).toString(), read(lastIndexed(StatementOfAccount, "A")));
    assertEquals(issued.get(1).toString(), read(secondToLastIndexed(StatementOfAccount, "A")));
    assertEquals(issued.get(2).toString(), read(lastIndexed(StatementOfAccount, "B")));
    // The only one is both the last and the second to last
    assertEquals(issued.get(2).toString(), read(secondToLastIndexed(StatementOfAccount, "B")));
  }

  private String read(EntitySelector selector) {
    return (String) machine.onEvent(new EventTrigger<Void, Void, String>(new EventSpec<>(Read, d -> d), List.of(_ -> selector), Statement, false))
        .blockFirst()
        .getUnmarshalledData();
  }

}
