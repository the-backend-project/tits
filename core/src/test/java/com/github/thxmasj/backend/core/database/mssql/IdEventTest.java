package com.github.thxmasj.backend.core.database.mssql;

import static com.github.thxmasj.backend.core.EntityModel.Begin;
import static com.github.thxmasj.backend.core.EntitySelector.lastInIdGroup;
import static com.github.thxmasj.backend.core.EntitySelector.secondToLastInIdGroup;
import static com.github.thxmasj.backend.core.EventTrigger.trigger;
import static com.github.thxmasj.backend.core.TransitionModelBuilder.WithEvent.onEvent;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.github.thxmasj.backend.core.BasicEventType;
import com.github.thxmasj.backend.core.BasicEventType.ReadOnly;
import com.github.thxmasj.backend.core.DataType;
import com.github.thxmasj.backend.core.EntityId;
import com.github.thxmasj.backend.core.EntityModel;
import com.github.thxmasj.backend.core.EntitySelector;
import com.github.thxmasj.backend.core.Event;
import com.github.thxmasj.backend.core.EventTrigger;
import com.github.thxmasj.backend.core.EventTrigger.EventSpec;
import com.github.thxmasj.backend.core.EventType;
import com.github.thxmasj.backend.core.SecondaryId;
import com.github.thxmasj.backend.core.SecondaryIdModel;
import com.github.thxmasj.backend.core.State;
import com.github.thxmasj.backend.core.StateMachine;
import com.github.thxmasj.backend.core.TransitionModelBuilder.TransitionModel;
import com.github.thxmasj.backend.core.test.Init;
import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

public class IdEventTest {

  enum States implements State { Opened, Issued }

  record StatementNumber(String account, long number) {}

  static SecondaryIdModel<String> AccountNumber = SecondaryIdModel.of(
      "AccountNumber",
      UUID.fromString("0c3f6a0e-2b51-4d55-9f43-a7a1d7f86d2b"),
      DataType.string()
  );
  static SecondaryIdModel<StatementNumber> StatementNumberId = SecondaryIdModel.of(
      "StatementNumber",
      UUID.fromString("b9f6e6a4-7a0d-4c1f-a5a9-1f7b0c8e3d42"),
      DataType.json(StatementNumber.class),
      new SecondaryIdModel.Group<>() {
        @Override public boolean isInitial(StatementNumber value) {return value.number() == 1;}
        @Override public SecondaryId<StatementNumber> initial(Object group) {return new SecondaryId<>(StatementNumberId, new StatementNumber((String) group, 1));}
        @Override public SecondaryId<StatementNumber> next(SecondaryId<StatementNumber> current) {return new SecondaryId<>(StatementNumberId, new StatementNumber(current.data().account(), current.data().number() + 1));}
        @Override public Object group(StatementNumber value) {return value.account();}
      }
  );

  static EntityModel Account = EntityModel.of("Account", UUID.fromString("5d1b3c86-1b0e-4f7e-8a8c-2d6c9f0b7e15"));
  static EntityModel Statement = EntityModel.of("Statement", UUID.fromString("e4a7d2f9-6c3b-4e8a-9b1d-7f5c0a2e8d36"));

  static EventType<String, String> Open = BasicEventType.of("Open", UUID.fromString("a2d4f6b8-1c3e-4a5b-8d7f-9e0a1b2c3d4e"), DataType.string());
  static EventType<Void, Void> Touch = BasicEventType.of("Touch", UUID.fromString("f1e2d3c4-b5a6-4978-8a6b-5c4d3e2f1a0b"));
  static EventType<String, StatementNumber> Issue = BasicEventType.of("Issue", UUID.fromString("3b5d7f9a-2c4e-4a6b-8c0d-1e2f3a4b5c6d"), DataType.string(), DataType.json(StatementNumber.class));
  static EventType<Void, StatementNumber> Read = new ReadOnly<>("Read", UUID.fromString("7c9e1a3b-5d7f-4b9a-8c1e-3f5a7b9c1d2e"), DataType.none(), DataType.json(StatementNumber.class));

  private static final String schema = UUID.randomUUID().toString();
  private static final StateMachine machine = Init.stateMachine(schema, Map.of(
      Account, Map.<State, List<TransitionModel<?, ?>>>of(
          Begin, List.of(
              onEvent(Open).to(States.Opened)
                  .assembleInput()
                  .newIdentifier(AccountNumber, d -> d)
                  .output(d -> d.t2().isRejected() ? "Duplicate of " + d.t2().rejected().log().entityId().value() : d.t1())
          ),
          States.Opened, List.of(onEvent(Touch).toSelf().output())
      ),
      Statement, Map.<State, List<TransitionModel<?, ?>>>of(
          Begin, List.of(
              onEvent(Issue).to(States.Issued)
                  .assembleInput()
                  .newIdentifierInGroup(StatementNumberId, d -> d)
                  .output(d -> d.t2().accepted().id().data())
          ),
          States.Issued, List.of(onEvent(Read).toSelf().assemble(c -> c.log().one(Issue)).output(d -> d))
      )
  ));

  @Test
  public void idIsAnEventOfASeparateIdEntity() throws SQLException {
    Event<?> opened = machine.onEvent(trigger(Open, Account), "123").blockFirst();

    var log = new EventsByEntityId(Init.dataSource(), List.of(Account), schema, Clock.systemUTC())
        .execute(Account, List.of(Open, Touch), new EntityId.UUID(opened.entityId()))
        .block();
    assertEquals(List.of(Open.name()), log.events().stream().map(Event::typeName).toList());
    assertEquals(List.of("1:" + AccountNumber.id()), idEvents(opened.entityId()));
  }

  @Test
  public void entityIsFoundByItsId() {
    Event<?> opened = machine.onEvent(trigger(Open, Account), "456").blockFirst();

    Event<?> touched = machine.onEvent(trigger(Touch, Account, AccountNumber, "456")).blockFirst();

    assertEquals(opened.entityId(), touched.entityId());
    assertEquals(2, touched.eventNumber());
  }

  @Test
  public void duplicateIdIsRejectedWithTheLogOfTheEntityHavingIt() {
    Event<?> first = machine.onEvent(trigger(Open, Account), "789").blockFirst();

    Event<?> second = machine.onEvent(trigger(Open, Account), "789").blockFirst();

    assertEquals("Duplicate of " + first.entityId(), second.getUnmarshalledData());
  }

  @Test
  public void groupIsOrderedByCreation() {
    List<StatementNumber> issued = new ArrayList<>();
    for (String account : List.of("A", "A", "B", "A"))
      issued.add((StatementNumber) machine.onEvent(trigger(Issue, Statement), account).blockFirst().getUnmarshalledData());

    assertEquals(
        List.of(new StatementNumber("A", 1), new StatementNumber("A", 2), new StatementNumber("B", 1), new StatementNumber("A", 3)),
        issued
    );
    assertEquals(new StatementNumber("A", 3), read(lastInIdGroup(StatementNumberId, "A")));
    assertEquals(new StatementNumber("A", 2), read(secondToLastInIdGroup(StatementNumberId, "A")));
    assertEquals(new StatementNumber("B", 1), read(lastInIdGroup(StatementNumberId, "B")));
  }

  private StatementNumber read(EntitySelector selector) {
    return (StatementNumber) machine.onEvent(new EventTrigger<Void, Void, StatementNumber>(new EventSpec<>(Read, d -> d), List.of(_ -> selector), Statement, false))
        .blockFirst()
        .getUnmarshalledData();
  }

  private List<String> idEvents(UUID entityId) throws SQLException {
    try (
        var connection = Init.dataSource().getConnection();
        var statement = connection.prepareStatement("SELECT EventNumber, Type FROM [" + schema + "].[IdEvent] WHERE EntityId=? ORDER BY EventNumber")
    ) {
      statement.setObject(1, entityId);
      var rs = statement.executeQuery();
      List<String> rows = new ArrayList<>();
      while (rs.next())
        rows.add(rs.getInt(1) + ":" + rs.getObject(2, UUID.class));
      return rows;
    }
  }

}
