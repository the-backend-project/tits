package com.github.thxmasj.backend.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.github.thxmasj.backend.core.Listener.Change;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

public class JsonLinesListenerTest {

  private static final UUID entityId = UUID.fromString("9f0c5a2e-1b3d-4c5e-8f7a-6b5c4d3e2f1a");
  // 12:00:00.123 UTC
  private static final ZonedDateTime timestamp = ZonedDateTime.of(2026, 11, 13, 13, 0, 0, 123_000_000, ZoneId.of("Europe/Oslo"));

  private final List<String> lines = new ArrayList<>();
  private final JsonLinesListener listener = new JsonLinesListener(
      lines::add,
      Clock.fixed(Instant.parse("2026-11-13T12:00:01Z"), ZoneOffset.UTC)
  );

  @Test
  public void anEventIsWrittenAsALineOfJsonWithItsSeverity() {
    listener.changeAccepted("SYS-1234", List.of(change(7, "Attempts exhausted", Severity.Error)));

    assertEquals(
        List.of(
            "{\"time\":\"2026-11-13T12:00:00.123Z\",\"severity\":\"ERROR\","
                + "\"message\":\"Attempts exhausted on Notification 9f0c5a2e-1b3d-4c5e-8f7a-6b5c4d3e2f1a\","
                + "\"entityModel\":\"Notification\",\"entityId\":\"9f0c5a2e-1b3d-4c5e-8f7a-6b5c4d3e2f1a\","
                + "\"eventNumber\":7,\"eventType\":\"Attempts exhausted\",\"correlationId\":\"SYS-1234\"}"
        ),
        lines
    );
  }

  @Test
  public void everyEventOfAChangeIsWrittenInTheOrderGiven() {
    listener.changeAccepted("1", List.of(
        change(1, "Request dispatched", Severity.Info),
        change(2, "Timeout expired", Severity.Warning),
        change(3, "Attempts exhausted", Severity.Error),
        change(4, "Money lost", Severity.Critical)
    ));

    assertEquals(List.of("INFO", "WARNING", "ERROR", "CRITICAL"), lines.stream().map(l -> parse(l).get("severity")).toList());
    assertEquals(List.of(1, 2, 3, 4), lines.stream().map(l -> parse(l).get("eventNumber")).toList());
  }

  @Test
  public void delayedEventsAndConditionalTriggersAreNotWritten() {
    Change.Entity entity = new Change.Entity("Notification", entityId);
    listener.changeAccepted("1", List.of(
        new Change(entity, new Change.DelayedEvent(2, "Timeout expired", timestamp.plusSeconds(30))),
        new Change(entity, null)
    ));

    assertEquals(List.of(), lines);
  }

  @Test
  public void aLineBreakInAValueDoesNotBreakTheLine() {
    // The correlation id is taken from a request header
    String correlationId = "1\n{\"severity\":\"CRITICAL\"}";
    listener.changeAccepted(correlationId, List.of(change(1, "Request dispatched", Severity.Info)));

    assertEquals(1, lines.size());
    assertFalse(lines.getFirst().contains("\n"));
    assertEquals(correlationId, parse(lines.getFirst()).get("correlationId"));
  }

  @Test
  public void aFailureToProcessADelayedTriggerIsWrittenAsAnError() {
    listener.processNextDelayedTriggerFailed(new IllegalStateException("No connection"));

    assertEquals(
        List.of(
            "{\"time\":\"2026-11-13T12:00:01Z\",\"severity\":\"ERROR\","
                + "\"message\":\"Processing the next delayed trigger failed: java.lang.IllegalStateException: No connection\"}"
        ),
        lines
    );
  }

  private static Change change(int eventNumber, String eventType, Severity severity) {
    return new Change(
        new Change.Entity("Notification", entityId),
        new Change.Event(eventNumber, eventType, severity, timestamp)
    );
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> parse(String line) {
    return DataType.objectMapper.readValue(line, Map.class);
  }

}
