package com.github.thxmasj.backend.core;

import com.github.thxmasj.backend.core.StateMachine.ProcessResult.Accepted;
import com.github.thxmasj.backend.core.StateMachine.ProcessResult.IdExists;
import com.github.thxmasj.backend.core.TransitionStep.OutputStep;
import com.github.thxmasj.backend.core.TransitionStep.RejectedStep;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;
import reactor.core.publisher.Mono;

/// The calculation of the changes of an event, by the steps of its transition and of the transitions it triggers. The
/// result of each step is recorded, such that the changes can be calculated again from the trigger of an id found to
/// exist when storing them: The steps before the trigger are then not executed again, but get the results recorded for
/// them, so the transition steps up to the trigger are the same.
public final class Calculation {

  /// @param context the transition step the step was taken from
  /// @param result  the result of the step, or null if it failed
  /// @param failure the failure of the step, or null if it has a result
  private record Step(TransitionStep<?> context, Object result, Throwable failure) {}

  // The steps executed, in order
  private final List<Step> steps = new ArrayList<>();
  private final List<IdExists<?>> existingIds = new ArrayList<>();
  // The number of steps taken by the ongoing calculation. The steps recorded from here on are those of the previous
  // calculation that are not executed again.
  private int position;

  /// The result of a step in the given transition step, which is the result of executing it, unless the changes are
  /// calculated again and the step was executed before the trigger they are calculated from. The steps must be taken
  /// one at a time, i.e. the execution of a step can't take other steps.
  @SuppressWarnings("unchecked")
  <T> Mono<T> step(TransitionStep<?> context, Supplier<Mono<T>> execution) {
    return Mono.defer(() -> {
      if (position < steps.size()) {
        Step recorded = steps.get(position);
        if (recorded.context() == context) {
          position++;
          return recorded.failure() != null ? Mono.error(recorded.failure()) : Mono.just((T) recorded.result());
        }
        // Not the step that was executed at this point, so no more of the recorded steps are valid
        steps.subList(position, steps.size()).clear();
      }
      return Mono.defer(execution)
          .doOnNext(result -> record(new Step(context, result, null)))
          .doOnError(failure -> record(new Step(context, null, failure)));
    });
  }

  private void record(Step step) {
    steps.add(step);
    position = steps.size();
  }

  /// The ids found to exist when storing the changes
  List<IdExists<?>> existingIds() {
    return Collections.unmodifiableList(existingIds);
  }

  /// Prepares for calculating the changes again, with an id found to exist when storing them. The steps from the first
  /// trigger of the id on are then executed again, with the id existing as the result of triggering it.
  ///
  /// @return false if calculating again would not change anything, as the id is not triggered by a step, or as it was
  ///         found to exist before
  boolean recalculateWith(IdExists<?> existing) {
    // The triggers of an id found to exist result in it existing. So when it is found to exist once more, it is not
    // recognised by the data it is triggered with, and would be found to exist over and over again.
    if (existingIds.stream().anyMatch(known -> isSameId(known.idEvent(), existing.idEvent())))
      return false;
    int trigger = firstTrigger(existing.idEvent());
    if (trigger < 0)
      return false;
    // Whether a rejected result is handled is a state of the result, set by the steps after it. That can't be undone
    // for the steps to be executed again, so all the steps are executed again if there is one before the trigger.
    if (steps.subList(0, trigger).stream().anyMatch(step -> step.result() instanceof RejectedStep))
      trigger = 0;
    existingIds.add(existing);
    steps.subList(trigger, steps.size()).clear();
    position = 0;
    return true;
  }

  /// The index of the first step triggering the id event, i.e. the step resulting in the input step of the
  /// transition calculating it, or -1 if there is none
  private int firstTrigger(Event<?> idEvent) {
    for (Step step : steps) {
      if (step.result() instanceof OutputStep<?> output && isCalculationOf(idEvent, output)) {
        var initial = output.inputStep();
        for (int i = 0; i < steps.size(); i++) {
          if (steps.get(i).result() == initial)
            return i;
        }
      }
    }
    return -1;
  }

  private static boolean isCalculationOf(Event<?> idEvent, OutputStep<?> output) {
    return output.stepOutput() instanceof Accepted<?> accepted
        && accepted.entityModel() instanceof IdEntityModel
        && isSameId(accepted.event(), idEvent);
  }

  private static boolean isSameId(Event<?> idEvent, Event<?> other) {
    return idEvent.type().id().equals(other.type().id())
        && Arrays.equals(idEvent.getMarshalledData(), other.getMarshalledData());
  }

}
