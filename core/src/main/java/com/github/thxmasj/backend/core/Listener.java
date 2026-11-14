package com.github.thxmasj.backend.core;

import com.github.thxmasj.backend.core.message.http.HttpResponseMessage;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

/// Is told what a state machine does. A listener implements the methods it is interested in; the others do nothing.
/// [JsonLinesListener] is a listener for monitoring.
public interface Listener {

    record Change(
        Entity entity,
        Event event
    ) {
      public record Entity(
          String model,
          UUID id
      ) {
        @Override public String toString() {
          return model + "[id=" + id + "]";
        }
      }

      public static class Event {

        private final int number;
        private final String type;
        private final Severity severity;
        private final ZonedDateTime timestamp;

        public Event(int number, String type) {
          this(number, type, Severity.Info, null);
        }

        public Event(int number, String type, Severity severity, ZonedDateTime timestamp) {
          this.number = number;
          this.type = type;
          this.severity = severity;
          this.timestamp = timestamp;
        }

        public int number() {return number;}

        public String type() {return type;}

        /// The severity declared on the transition which accepted the event
        public Severity severity() {return severity;}

        /// The time of the event, or null for a delayed event, which is yet to happen
        public ZonedDateTime timestamp() {return timestamp;}

      }

      public static class DelayedEvent extends Event {
        private final ZonedDateTime after;

        public DelayedEvent(int number, String type, ZonedDateTime after) {
          super(number, type);
          this.after = after;
        }

        public ZonedDateTime after() {return after;}
      }
    }

    /// The changes of an event are stored. A change is an accepted event, a delayed event, which is a
    /// [Change.DelayedEvent], or a conditional trigger, which has no event. The changes are given in the order they
    /// were calculated.
    default void changeAccepted(
        String correlationId,
        List<Change> changes
    ) {}

  default void processNextDelayedTriggerFailed(Throwable t) {}

  default void forwardingAttempt(
      UUID requestId,
      EntityModel entityModel,
      String queue,
      UUID entityId,
      int eventNumber,
      ZonedDateTime enqueuedAt,
      int attempt,
      String correlationId
  ) {}

  default void forwardingCompleted(
      UUID requestId,
      EntityModel entityModel,
      String queue,
      UUID entityId,
      int eventNumber,
      ZonedDateTime enqueuedAt,
      int attempt,
      String correlationId,
      HttpResponseMessage receipt,
      String reason
  ) {}

  default void forwardingBackedOff(
      UUID requestId,
      EntityModel entityModel,
      String queue,
      UUID entityId,
      int eventNumber,
      ZonedDateTime enqueuedAt,
      int attempt,
      String correlationId,
      String reason,
      ZonedDateTime nextAttemptAt,
      Duration backoff
  ) {}

  default void forwardingDead(
      UUID requestId,
      EntityModel entityModel,
      String queue,
      UUID entityId,
      int eventNumber,
      ZonedDateTime enqueuedAt,
      int attempt,
      String correlationId,
      String reason
  ) {}

  default void forwardingDeadByExhaustion(
      UUID requestId,
      EntityModel entityModel,
      String queue,
      UUID entityId,
      int eventNumber,
      ZonedDateTime enqueuedAt,
      int attempt,
      String correlationId,
      String reason
  ) {}

  default void forwardingDeadlock(String queue) {}

  default void forwardingError(String queue, Throwable error) {}

  default void forwardingEmptyQueue(String queue) {}
}
