package com.github.thxmasj.backend.core;

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
        private final boolean stored;

        public Event(int number, String type) {
          this(number, type, Severity.Info, null);
        }

        public Event(int number, String type, Severity severity, ZonedDateTime timestamp) {
          this(number, type, severity, timestamp, true);
        }

        public Event(int number, String type, Severity severity, ZonedDateTime timestamp, boolean stored) {
          this.number = number;
          this.type = type;
          this.severity = severity;
          this.timestamp = timestamp;
          this.stored = stored;
        }

        /// The number of the event in the event log of the entity. An event which is not stored has the number it
        /// would have had, which the next event stored on the entity gets.
        public int number() {return number;}

        /// Whether the event is stored. An event of a type which is not stored, or of an entity of the session only,
        /// is not (see [EventType#stored()] and [EntityModel#stored()]), so the event log does not have it.
        public boolean stored() {return stored;}

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
    /// were calculated, with the accepted events which are not stored too (see [Change.Event#stored()]).
    default void changeAccepted(
        String correlationId,
        List<Change> changes
    ) {}

  default void processNextDelayedTriggerFailed(Throwable t) {}

  /// The transition of the event on the entity failed, as a function of it, e.g. an assembler, guard or data adapter,
  /// threw an exception, so nothing of the change is stored. It is told once, for the transition the exception was
  /// thrown in, also if that one was triggered by another. The exception is passed on to the one who triggered the
  /// event.
  default void transitionFailed(
      String correlationId,
      EntityModel entityModel,
      UUID entityId,
      EventType<?, ?> eventType,
      Throwable failure
  ) {}
}
