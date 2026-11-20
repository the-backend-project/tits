package com.github.thxmasj.backend.core;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/// A listener for monitoring, which writes a line of JSON for every event the state machine accepts, with the
/// severity declared on the transition of the event:
///
/// ```json
/// {"time":"2026-11-13T12:00:00.123Z","severity":"ERROR","message":"Attempts exhausted on Notification 9f0c5a2e-…","entityModel":"Notification","entityId":"9f0c5a2e-…","eventNumber":7,"eventType":"Attempts exhausted","correlationId":"SYS-1234"}
/// ```
///
/// The lines go to stdout, for the execution environment to collect, and to alert on by their `severity`, which is
/// `INFO`, `WARNING`, `ERROR` or `CRITICAL`. The data of the events is not written, as it may be sensitive.
///
/// An event which is not stored (see [Change.Event#stored()]) is written too, with `"stored":false` and no event
/// number, as the event log does not have it. Delayed events and conditional triggers are not written before their
/// events happen. A transition which failed, as
/// a function of it threw an exception, is written as a line of severity `ERROR` with the entity, the event type and
/// the exception, but no event number, as no event was stored. A failure to process a delayed trigger is written as a
/// line of severity `ERROR` with no event, and with the failure as it is for its message.
public class JsonLinesListener implements Listener {

  private final Consumer<String> lines;
  private final Clock clock;

  /// A listener writing to stdout
  public JsonLinesListener() {
    // Not a method reference, which would hold on to the stdout of now
    this(line -> System.out.println(line), Clock.systemUTC());
  }

  /// @param lines is given each line, without a line break
  /// @param clock gives the time of the lines which are not of events
  public JsonLinesListener(Consumer<String> lines, Clock clock) {
    this.lines = lines;
    this.clock = clock;
  }

  @Override
  public void changeAccepted(String correlationId, List<Change> changes) {
    for (Change change : changes) {
      Change.Event event = change.event();
      if (event == null || event instanceof Change.DelayedEvent)
        continue;
      Map<String, Object> line = line(
          event.timestamp().toInstant(),
          event.severity(),
          event.type() + " on " + change.entity().model() + " " + change.entity().id()
      );
      line.put("entityModel", change.entity().model());
      line.put("entityId", change.entity().id().toString());
      // An event which is not stored has no number in the event log
      if (event.stored())
        line.put("eventNumber", event.number());
      line.put("eventType", event.type());
      if (!event.stored())
        line.put("stored", false);
      line.put("correlationId", correlationId);
      write(line);
    }
  }

  @Override
  public void processNextDelayedTriggerFailed(Throwable t) {
    write(line(clock.instant(), Severity.Error, "Processing the next delayed trigger failed: " + t));
  }

  @Override
  public void transitionFailed(
      String correlationId,
      EntityModel entityModel,
      UUID entityId,
      EventType<?, ?> eventType,
      Throwable failure
  ) {
    Map<String, Object> line = line(
        clock.instant(),
        Severity.Error,
        eventType.name() + " on " + entityModel.name() + " " + entityId + " failed: " + failure
    );
    line.put("entityModel", entityModel.name());
    line.put("entityId", entityId.toString());
    line.put("eventType", eventType.name());
    line.put("correlationId", correlationId);
    write(line);
  }

  private static Map<String, Object> line(Instant time, Severity severity, String message) {
    Map<String, Object> line = new LinkedHashMap<>();
    line.put("time", time.toString());
    line.put("severity", severity.name().toUpperCase(Locale.ROOT));
    line.put("message", message);
    return line;
  }

  /// A map is written without reflection, and so needs no reachability metadata in a native image. Line breaks in
  /// the values are escaped, so a line is a single line whatever e.g. the correlation id is.
  private void write(Map<String, Object> line) {
    lines.accept(DataType.objectMapper.writeValueAsString(line));
  }

}
