package com.github.thxmasj.backend.core;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
/// Delayed events and conditional triggers are not written before their events happen. A failure to process a delayed
/// trigger is written as a line of severity `ERROR` with no event, and with the failure as it is for its message. It
/// is the only failure a state machine tells its listener of.
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
      line.put("eventNumber", event.number());
      line.put("eventType", event.type());
      line.put("correlationId", correlationId);
      write(line);
    }
  }

  @Override
  public void processNextDelayedTriggerFailed(Throwable t) {
    write(line(clock.instant(), Severity.Error, "Processing the next delayed trigger failed: " + t));
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
