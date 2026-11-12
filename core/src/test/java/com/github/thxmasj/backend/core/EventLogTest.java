package com.github.thxmasj.backend.core;

import static java.util.UUID.randomUUID;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.github.thxmasj.backend.core.BasicEventType.Rollback.Data;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

public class EventLogTest {

  @Test
  public void whenOneEventInLogEffectiveEventsAreSameAsEvents() {
    List<Event<?>> events = List.of(
      new Event<>(randomUUID(), 1, new BasicEventType.DataLess("Test", randomUUID()), Clock.systemUTC())
    );
    EventLog log = new EventLog(null, null, events);
    assertEquals(events, log.effectiveEvents());
  }

  @Test
  public void whenTwoEventsInLogEffectiveEventsAreSameAsEvents() {
    List<Event<?>> events = List.of(
        new Event<>(randomUUID(), 1, new BasicEventType.DataLess("Test1", randomUUID()), Clock.systemUTC()),
        new Event<>(randomUUID(), 2, new BasicEventType.DataLess("Test2", randomUUID()), Clock.systemUTC())
    );
    EventLog log = new EventLog(null, null, events);
    assertEquals(events, log.effectiveEvents());
  }

  @Test
  public void whenTwoEventsAndRollbackInLogEffectiveEventsAreNone() {
    List<Event<?>> events = List.of(
        new Event<>(randomUUID(), 1, new BasicEventType.DataLess("Test1", randomUUID()), Clock.systemUTC()),
        new Event<>(randomUUID(), 2, new BasicEventType.DataLess("Test2", randomUUID()), Clock.systemUTC()),
        new Event<>(randomUUID(), 3, BuiltinEventTypes.Rollback, Clock.systemUTC(), new Data(0, "testing"))
        //new Event.Rollback(3, 0, "testing", Clock.systemUTC())
    );
    EventLog log = new EventLog(null, null, events);
    assertEquals(List.of(), log.effectiveEvents());
  }

  @Test
  public void eventLogPreservesEntityModel() {
    EntityModel model = EntityModel.of("TestModel", randomUUID());
    UUID entityId = randomUUID();
    List<Event<?>> events = List.of(
        new Event<>(entityId, 1, new BasicEventType.DataLess("Test1", randomUUID()), Clock.systemUTC())
    );
    EventLog log = new EventLog(model, entityId, events);
    assertEquals(model, log.entityModel());
    assertEquals(entityId, log.entityId());
    assertEquals(events, log.events());
  }

}
