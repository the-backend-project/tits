package com.github.thxmasj.backend.core.internal.database;

import com.github.thxmasj.backend.core.BasicEventType;
import com.github.thxmasj.backend.core.EntityModel;
import com.github.thxmasj.backend.core.Event;
import com.github.thxmasj.backend.core.Severity;
import com.github.thxmasj.backend.core.StateMachine.ProcessResult;
import com.github.thxmasj.backend.core.TriggerType;
import com.github.thxmasj.backend.core.internal.DelayedEvent;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;
import reactor.core.publisher.Mono;

/// Stores the changes of a transition atomically
public interface ChangeState {

  /// Stores all the changes, or none of them if it fails. A change stores, in the order given:
  ///
  /// - its new event, if any, as the next event of its entity, creating the entity with its entity model on its first
  ///   event (an id or index entity has no entity model of its own stored). A new event of an entity removes the
  ///   delayed trigger of the entity with the same or a lower event number, as that number is then taken, and a new
  ///   event caused by a conditional trigger removes that trigger, so that it is not triggered again.
  /// - its conditional trigger, if any, waiting for its condition on the entities indexed by its key
  /// - its delayed event, if any, as the delayed trigger of the entity, replacing the one with a lower event number
  ///
  /// The new event of an id entity also takes its id, i.e. the data of its type, which no other id event may have.
  ///
  /// @param timestamp the time of the new events and conditional triggers
  /// @param correlationId the correlation id of the delayed triggers
  /// @throws EventAlreadyExists (as the error of the result) if an entity already has an event with the number of a
  ///                            new event, e.g. as another transition on the entity has been stored since its events
  ///                            were read, or if the first event of an entity is stored for an existing entity
  /// @throws IdEventAlreadyExists (as the error of the result) if another id event of the same type has the same data
  ///                              as a new id event
  Mono<Void> execute(ZonedDateTime timestamp, String correlationId, List<Change> changes);

  public interface Change {

    EntityModel entityModel();
    
    UUID entityId();
    
    Event<?> newEvent();

    DelayedEvent<?> delayedEvent();

    default ConditionalTrigger<?> conditionalTrigger() {
      return null;
    }

    /// The severity of this change's new event, for the listener of the state machine. It is not stored.
    default Severity severity() {
      return Severity.Info;
    }

    /**
     * The type of the stored trigger that caused this change's new event, if any.
     */
    default TriggerType triggerType() {
      return null;
    }

    /**
     * The id of the stored trigger that caused this change's new event. The trigger is deleted together with the new
     * event, so that it will not be triggered again.
     */
    default UUID triggerId() {
      return null;
    }

    static Change fromConditionalTrigger(ConditionalTrigger<?> conditionalTrigger) {
      return new Change() {
        @Override
        public EntityModel entityModel() {
          return conditionalTrigger.entityModel();
        }

        @Override
        public UUID entityId() {
          return conditionalTrigger.entityId();
        }

        @Override
        public Event<?> newEvent() {
          return null;
        }

        @Override
        public DelayedEvent<?> delayedEvent() {
          return null;
        }

        @Override
        public ConditionalTrigger<?> conditionalTrigger() {
          return conditionalTrigger;
        }

        @Override
        public String toString() {
          return entityModel().name() + ":" +
              conditionalTrigger.entityId() + ":trigger(conditional):[" +
              conditionalTrigger.type().name() + "]";
        }

      };
    }

    static Change fromAcceptedEvent(ProcessResult.Accepted<?> acceptedEvent) {
      return new Change() {
        @Override
        public EntityModel entityModel() {
          return acceptedEvent.entityModel();
        }

        @Override
        public UUID entityId() {
          return acceptedEvent.event().entityId();
        }

        @Override
        public Event<?> newEvent() {
          return acceptedEvent.event();
        }

        @Override
        public Severity severity() {
          return acceptedEvent.severity();
        }

        @Override
        public TriggerType triggerType() {
          return acceptedEvent.triggerType();
        }

        @Override
        public UUID triggerId() {
          return acceptedEvent.triggerId();
        }

        @Override
        public DelayedEvent<?> delayedEvent() {
          return null;
        }

        @Override
        public String toString() {
          return entityModel().name() + ":" +
              entityId() + ":ev:[" +
              newEvent().typeName() + "]:" +
              newEvent().eventNumber() + ":data=" +
              (newEvent().data() != null ? capString(newEvent().getUnmarshalledData().toString()) : "N/A") +
              (newEvent().type() instanceof BasicEventType.ReadOnly<?,?> ? " (read-only)" : "");
        }

      };
    }

    private static String capString(String s) {
      if (s == null) return null;
      if (s.length() > 50) return s.replace("\n", "↵").substring(0, 50) + "...";
      return s;
    }

    static Change fromDelayedEvent(DelayedEvent<?> delayedEvent) {
      return new Change() {
        @Override
        public EntityModel entityModel() {
          return delayedEvent.entityModel();
        }

        @Override
        public UUID entityId() {
          return delayedEvent.entityId();
        }

        @Override
        public Event<?> newEvent() {
          return null;
        }

        @Override
        public DelayedEvent<?> delayedEvent() {
          return delayedEvent;
        }

        @Override
        public String toString() {
          return entityModel().name() + ":" +
              delayedEvent.entityId() + ":ev:[" +
              delayedEvent.type().name() + "]:" +
              delayedEvent.eventNumber() +
              " after " + delayedEvent.after();
        }

      };
    }

  }

}
