package com.github.thxmasj.backend.core;

import static com.github.thxmasj.backend.core.StateMachine.chainToString;
import static com.github.thxmasj.backend.core.Tuples.tuple;
import static java.util.Optional.ofNullable;

import com.github.thxmasj.backend.core.StateMachine.ProcessResult;
import com.github.thxmasj.backend.core.StateMachine.ProcessResult.Accepted;
import com.github.thxmasj.backend.core.StateMachine.ProcessResult.Completed;
import com.github.thxmasj.backend.core.StateMachine.ProcessResult.IdExists;
import com.github.thxmasj.backend.core.StateMachine.ProcessResult.Pending;
import com.github.thxmasj.backend.core.StateMachine.ProcessResult.Rejected;
import com.github.thxmasj.backend.core.StateMachine.ProcessResult.UnknownId;
import com.github.thxmasj.backend.core.TransitionModelBuilder.TransitionContext;
import com.github.thxmasj.backend.core.Tuples.Tuple2;
import java.time.ZonedDateTime;
import java.util.UUID;

/// A step of the calculation of a transition. The steps of a transition are chained, each one referring to the one
/// before it, from the input step to the output step. A transition triggering an event on another entity continues
/// the chain with the steps of the triggered transition, from its input step to its output step.
public sealed interface TransitionStep<T> {

  TransitionStep<?> previous();

  T stepOutput();

  default InputStep<?> inputStep() {
    try {
      int depth = this instanceof OutputStep<?> ? 0 : 1;
      for (TransitionStep<?> c = this; c != null; c = c.previous()) {
        if (c instanceof OutputStep<?> _)
          depth++;
        if (c instanceof InputStep<?> i && --depth == 0)
          return i;
      }
    } catch (Exception e) {
      throw new IllegalStateException("Failed to find input step: " + e);
    }
    throw new IllegalStateException("No input step. We have: " + chainToString(this));
  }

  /// @param calculation the calculation the step is part of, which is the one of the step it is triggered from
  record InputStep<I>(
      TransitionStep<?> stage1,
      TransitionStep<?> previous,
      EventReference triggerEvent,
      TransitionModel<?, ?> transitionModel,
      State from,
      int eventNumber,
      EventLog log,
      ZonedDateTime timestamp,
      String correlationId,
      I input,
      StateMachine stateMachine,
      Calculation calculation,
      TriggerType triggerType,
      UUID triggerId
  ) implements TransitionStep<I>, TransitionContext<I> {
    public InputStep(
        TransitionStep<?> stage1,
        TransitionStep<?> previous,
        EventReference triggerEvent,
        TransitionModel<?, ?> transitionModel,
        State from,
        int eventNumber,
        EventLog log,
        ZonedDateTime timestamp,
        String correlationId,
        I input,
        StateMachine stateMachine,
        Calculation calculation
    ) {
      this(stage1, previous, triggerEvent, transitionModel, from, eventNumber, log, timestamp, correlationId, input, stateMachine, calculation, null, null);
    }
    @Override public I stepOutput() {return input;}
    @Override public String toString() {
      return "Initial " + log.entityModel().name() + "/" + log.entityId() + "/" + eventNumber + "/" + from.name() + "(stage1=" + (stage1 != null) + "): " + transitionModel + " with " + ofNullable(input).map(i -> i.getClass().getSimpleName()).orElse("-");
    }
  }

  record AssembleStep<T>(
      TransitionStep<?> previous,
      T stepOutput
  ) implements TransitionStep<T> {
    @Override public String toString() {
      return "Assemble" + this.hashCode();
    }
  }

  record TriggerStep<T, O1>(
      TransitionStep<ProcessResult<O1>> previous,
      TransitionStep<T> outer,
      EventTrigger<T, ?, O1> eventTrigger
  ) implements TransitionStep<Tuple2<T, ProcessResult<O1>>> {

    @Override
    public Tuple2<T, ProcessResult<O1>> stepOutput() {
      return tuple(outer.stepOutput(), previous.stepOutput());
    }

    @Override public String toString() {
      return "Trigger: " + eventTrigger.eventSpec().eventType().name() + "@" + eventTrigger.entityModel().name();
    }

  }

  record DelayedTriggerStep<T, I1>(
      TransitionStep<T> previous,
      DelayedEvent<I1> delayedEvent
  ) implements TransitionStep<T> {
    @Override public String toString() {
      return "Delayed trigger: " + delayedEvent.entityModel().name() + "/" + delayedEvent.type().name() + "/"
          + delayedEvent.entityId() + "/" + delayedEvent.eventNumber() + " after " + delayedEvent.after()
          + " (now is " + previous.inputStep().timestamp() + ")";
    }

    @Override
    public T stepOutput() {
      return previous.stepOutput();
    }
  }

  record ConditionalTriggerStep<T, I1>(
      TransitionStep<T> previous,
      ConditionalTrigger<I1> conditionalTrigger
  ) implements TransitionStep<T> {
    @Override public String toString() {
      return "Conditional trigger: " + conditionalTrigger.entityModel().name() + "/" + conditionalTrigger.type().name() + "/"
          + conditionalTrigger.entityId() + " when last event is one of "
          + conditionalTrigger.lastTypes().stream().map(EventType::name).toList();
    }

    @Override
    public T stepOutput() {
      return previous.stepOutput();
    }
  }

  record ChoiceStep<T>(
      TransitionStep<?> previous,
      TransitionStep<T> outer
  ) implements TransitionStep<T> {
    @Override public String toString() {
      return "Choice (outer: " + outer + ")";
    }

    @Override
    public T stepOutput() {
      return outer.stepOutput();
    }
  }

  record ActionStep<T, U>(
      TransitionStep<T> previous,
      ActionTrigger<U> actionTrigger
  ) implements TransitionStep<T> {
    @Override public String toString() {
      return "Action: " + actionTrigger.action().name();
    }

    @Override
    public T stepOutput() {
      return previous.stepOutput();
    }
  }

  record OutputStep<T>(
      TransitionStep<?> previous,
      ProcessResult<T> stepOutput
  ) implements TransitionStep<ProcessResult<T>> {
    @Override public String toString() {
      return "Output:" + switch (stepOutput) {
        case Accepted<?> a -> "Accepted:" + a.entityModel().name() + "/" + a.event().entityId() + "/" + a.event().typeName() + "/" + a.event().eventNumber();
        case Rejected<?> r -> "Rejected" + (r.isHandled() ? "[Handled]" : "[Unhandled]") + ":" + r.exception().entityModel().name() + "/" + r.exception().entityId() + r.eventType().name() + ":" + r.exception().getMessage();
        case Completed<?> c -> "Completed:" + c.choiceResult();
        case UnknownId<?> u -> "UnknownId:" + u.exception().getMessage() + "/ev=" + u.eventType().name();
        case IdExists<?> i -> "IdExists:" + i.idEvent().typeName() + "/" + i.log().entityModel().name() + "/" + i.log().entityId();
        case Pending<?, ?, ?> p -> "Pending:" + p.exception().entityModel() + "/" + p.exception().entityId() + ":" + p.exception().getMessage();
      };
    }

  }

  record PendingStep<T, I, O>(
      TransitionStep<?> previous,
      ProcessResult.Pending<T, I, O> stepOutput
  ) implements TransitionStep<ProcessResult<O>> {
    @Override public String toString() {
      return "Pending:" + stepOutput.exception().getMessage();
    }

  }

  record RejectedStep<O>(
      TransitionStep<?> previous,
      ProcessResult.Rejected<O> stepOutput
  ) implements TransitionStep<ProcessResult<O>> {
    @Override public String toString() {
      return "Rejected" + (stepOutput.isHandled() ? "[Handled]" : "[Unhandled]") + ":" + stepOutput.exception().getMessage();
    }
  }

  record IdExistsStep<O>(
      TransitionStep<?> previous,
      ProcessResult.IdExists<O> stepOutput
  ) implements TransitionStep<ProcessResult<O>> {
    @Override public String toString() {
      return "IdExists:" + stepOutput.idEvent().typeName() + "/" + stepOutput.log().entityModel().name() + "/" + stepOutput.log().entityId();
    }
  }

  record UnknownIdStep<O>(
      TransitionStep<?> previous,
      ProcessResult.UnknownId<O> stepOutput
  ) implements TransitionStep<ProcessResult<O>> {
    @Override public String toString() {
      return "UnknownId:" + stepOutput.exception().getMessage();
    }
  }

}
