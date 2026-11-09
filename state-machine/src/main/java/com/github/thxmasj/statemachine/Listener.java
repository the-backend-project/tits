package com.github.thxmasj.statemachine;

import com.github.thxmasj.statemachine.message.http.HttpResponseMessage;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;
import org.checkerframework.checker.nullness.qual.NonNull;

public interface Listener {

    record Change(
        Entity entity,
        ZonedDateTime timeout,
        Event event,
        List<String> secondaryIds
    ) {
      public record Entity(
          String model,
          UUID id,
          List<String> secondaryIds
      ) {
        @Override public @NonNull String toString() {
          return model + "[id=" + id + "]";
        }
      }

      public static class Event {

        private final int number;
        private final String type;

        public Event(int number, String type) {
          this.number = number;
          this.type = type;
        }

        public int number() {return number;}

        public String type() {return type;}

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

    void changeAccepted(
        String correlationId,
        List<Change> changes
    );

  void processNextDeadlineFailed(Throwable t);

  void forwardingAttempt(
      UUID requestId,
      EntityModel entityModel,
      String queue,
      EntityId entityId,
      int eventNumber,
      ZonedDateTime enqueuedAt,
      int attempt,
      String correlationId
  );

  void forwardingCompleted(
      UUID requestId,
      EntityModel entityModel,
      String queue,
      EntityId entityId,
      int eventNumber,
      ZonedDateTime enqueuedAt,
      int attempt,
      String correlationId,
      HttpResponseMessage receipt,
      String reason
  );

  void forwardingBackedOff(
      UUID requestId,
      EntityModel entityModel,
      String queue,
      EntityId entityId,
      int eventNumber,
      ZonedDateTime enqueuedAt,
      int attempt,
      String correlationId,
      String reason,
      ZonedDateTime nextAttemptAt,
      Duration backoff
  );

  void forwardingDead(
      UUID requestId,
      EntityModel entityModel,
      String queue,
      EntityId entityId,
      int eventNumber,
      ZonedDateTime enqueuedAt,
      int attempt,
      String correlationId,
      String reason
  );

  void forwardingDeadByExhaustion(
      UUID requestId,
      EntityModel entityModel,
      String queue,
      EntityId entityId,
      int eventNumber,
      ZonedDateTime enqueuedAt,
      int attempt,
      String correlationId,
      String reason
  );

  void forwardingDeadlock(String queue);

  void forwardingError(String queue, Throwable error);

  void forwardingEmptyQueue(String queue);
}
