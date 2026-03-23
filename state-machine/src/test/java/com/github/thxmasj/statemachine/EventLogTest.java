package com.github.thxmasj.statemachine;

import static com.github.thxmasj.statemachine.Tuples.tuple;
import static java.util.UUID.randomUUID;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.core.type.TypeReference;
import com.github.thxmasj.statemachine.BasicEventType.Rollback.Data;
import com.github.thxmasj.statemachine.EventType.DataType;
import com.github.thxmasj.statemachine.Tuples.Tuple2;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Test;

public class EventLogTest {

  @Test
  public void marshal() {
    EventType<Void, Tuple2<String, String>> type = BasicEventType.of("test", randomUUID(), new DataType<>(Void.class), new DataType<>(new TypeReference<>() {}, String.class, String.class));
    var event = new Event<>(
        randomUUID(), 1, type, Clock.systemUTC(),
        """
        {
          "t1":   "hey",
          "t2": "ho"
        }
        """);
    //new Event<>(1, type, Clock.systemUTC(), tuple("hey", "ho"));
    //System.out.println(event.getMarshalledData());
    Tuple2<String, String> data = event.getUnmarshalledData();
    System.out.println(data);
    var event2 = new Event<>(randomUUID(), 1, type, Clock.systemUTC(), tuple("This", "works!"));
    System.out.println(event2.getMarshalledData());
  }

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
        new Event<>(randomUUID(), 3, BuiltinEventTypes.Rollback, Clock.systemUTC(), new Data(0, "testing"))
        //new Event.Rollback(3, 0, "testing", Clock.systemUTC())
    );
    EventLog log = new EventLog(null, null, List.of(), events);
    assertEquals(List.of(), log.effectiveEvents());
  }

}
