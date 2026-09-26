package com.github.thxmasj.statemachine;

import static java.util.UUID.randomUUID;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.github.thxmasj.statemachine.BasicEventType.Rollback.Data;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Test;

public class EventLogTest {

  @Test
  public void whenOneEventInLogEffectiveEventsAreSameAsEvents() {
    List<Event<?>> events = List.of(
      new Event<>(randomUUID(), 1, new BasicEventType.DataLess("Test", randomUUID()), Clock.systemUTC())
    );
    EventLog log = new EventLog(null, null, List.of(), events);
    assertEquals(events, log.effectiveEvents());
  }

  @Test
  public void whenTwoEventsInLogEffectiveEventsAreSameAsEvents() {
    List<Event<?>> events = List.of(
        new Event<>(randomUUID(), 1, new BasicEventType.DataLess("Test1", randomUUID()), Clock.systemUTC()),
        new Event<>(randomUUID(), 2, new BasicEventType.DataLess("Test2", randomUUID()), Clock.systemUTC())
    );
    EventLog log = new EventLog(null, null, List.of(), events);
    assertEquals(events, log.effectiveEvents());
  }

  @Test
  public void whenTwoEventsAndRollbackInLogEffectiveEventsAreNone() {
    List<Event<?>> events = List.of(
        new Event<>(randomUUID(), 1, new BasicEventType.DataLess("Test1", randomUUID()), Clock.systemUTC()),
        new Event<>(randomUUID(), 2, new BasicEventType.DataLess("Test2", randomUUID()), Clock.systemUTC()),
        new Event<>(randomUUID(), 3, BuiltinEventTypes.Rollback, Clock.systemUTC(), new Data(0, 2, "testing"))
        //new Event.Rollback(3, 0, "testing", Clock.systemUTC())
    );
    EventLog log = new EventLog(null, null, List.of(), events);
    assertEquals(List.of(), log.effectiveEvents());
  }

  @Test
  public void eventLogPreservesEntityModel() {
    EntityModel model = EntityModel.of("TestModel", randomUUID());
    EntityId entityId = new EntityId.UUID(randomUUID());
    List<Event<?>> events = List.of(
        new Event<>(entityId.value(), 1, new BasicEventType.DataLess("Test1", randomUUID()), Clock.systemUTC())
    );
    EventLog log = new EventLog(model, entityId, List.of(), events);
    assertEquals(model, log.entityModel());
    assertEquals(entityId, log.entityId());
    assertEquals(events, log.events());
  }

}
