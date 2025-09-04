package com.github.thxmasj.statemachine;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Clock;
import java.util.List;
import java.util.UUID;
import com.github.thxmasj.statemachine.BasicEventType.Rollback.Data;
import org.junit.jupiter.api.Test;

public class EventLogTest {

  @Test
  public void whenOneEventInLogEffectiveEventsAreSameAsEvents() {
    List<Event<?>> events = List.of(
      new Event<>(1, new BasicEventType.DataLess("Test", UUID.randomUUID()), Clock.systemUTC())
    );
    EventLog log = new EventLog(null, null, List.of(), events);
    assertEquals(events, log.effectiveEvents());
  }

  @Test
  public void whenTwoEventsInLogEffectiveEventsAreSameAsEvents() {
    List<Event<?>> events = List.of(
        new Event<>(1, new BasicEventType.DataLess("Test1", UUID.randomUUID()), Clock.systemUTC()),
        new Event<>(2, new BasicEventType.DataLess("Test2", UUID.randomUUID()), Clock.systemUTC())
    );
    EventLog log = new EventLog(null, null, List.of(), events);
    assertEquals(events, log.effectiveEvents());
  }

  @Test
  public void whenTwoEventsAndRollbackInLogEffectiveEventsAreNone() {
    List<Event<?>> events = List.of(
        new Event<>(1, new BasicEventType.DataLess("Test1", UUID.randomUUID()), Clock.systemUTC()),
        new Event<>(2, new BasicEventType.DataLess("Test2", UUID.randomUUID()), Clock.systemUTC()),
        new Event<>(3, BuiltinEventTypes.Rollback, Clock.systemUTC(), new Data(0, "testing"))
        //new Event.Rollback(3, 0, "testing", Clock.systemUTC())
    );
    EventLog log = new EventLog(null, null, List.of(), events);
    assertEquals(List.of(), log.effectiveEvents());
  }

}
