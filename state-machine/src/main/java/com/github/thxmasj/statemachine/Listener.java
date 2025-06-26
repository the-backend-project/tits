package com.github.thxmasj.statemachine;

import com.github.thxmasj.statemachine.database.ChangeRaced;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

public interface Listener {

    void clientRequestFailed(
            String correlationId,
            EntityId entityId,
            Event requestEvent,
            Throwable t
    );

    void rollbackFailed(
            String correlationId,
            EntityId entityId,
            Throwable t
    );

    void actionExecuted(
            String correlationId,
            EntityId entityId,
            String actionName,
            String currentState,
            Event output
    );

    void inconsistentState(
            String correlationId,
            EntityId entityId,
            String sourceState,
            String details
    );

    void resolveStateFailed(
        String correlationId,
        EntityId entityId,
        String sourceState,
        Event resolveEvent,
        String details
    );

    record Change(
        Entity entity,
        State sourceState,
        State targetState,
        List<Event> events,
        List<String> secondaryIds,
        List<String> incomingRequests,
        List<String> outgoingResponses,
        List<String> outgoingRequests,
        List<String> incomingResponses
    ) {
      public record Entity(
          EntityModel type,
          EntityId id,
          List<String> secondaryIds
      ) {}
      public record Event(
          int number,
          EventType type,
          String data
      ) {}
    }

    void changeAccepted(
        String correlationId,
        List<Change> changes
    );

    void changeFailed(
            String correlationId,
            List<Change> changes,
            Throwable t
    );

    void repeatedRequest(String correlationId, EntityId entityId, String clientId, String messageId);

    void changeRaced(String correlationId, List<Change> changes, ChangeRaced cause);

    void processNextDeadlineFailed(Throwable t);

    void forwardingAttempt(UUID requestId, String queue, ZonedDateTime enqueuedAt, int attempt, EntityId entityId, int eventNumber, String correlationId);

    void forwardingCompleted(UUID requestId, String queue, ZonedDateTime enqueuedAt, int attempt, EntityId entityId, int eventNumber, String correlationId, String receipt, String reason);

    void forwardingBackedOff(UUID requestId, String queue, ZonedDateTime enqueuedAt, int attempt, EntityId entityId, int eventNumber, String correlationId, String reason, ZonedDateTime nextAttemptAt, Duration backoff);

    void forwardingDead(UUID requestId, EntityId entityId, String queue, ZonedDateTime enqueuedAt, int attempt, int eventNumber, String correlationId, String reason);

    void forwardingDeadByExhaustion(UUID requestId, EntityId entityId, String queue, ZonedDateTime enqueuedAt, int attempt, int eventNumber, String correlationId, String reason);

    void forwardingRaced(String queue);

    void forwardingDeadlock(String queue);

    void forwardingError(String queue, Throwable error);

    void forwardingEmptyQueue(String queue);
}
