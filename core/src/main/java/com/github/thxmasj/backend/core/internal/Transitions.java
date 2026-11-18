package com.github.thxmasj.backend.core.internal;

import com.github.thxmasj.backend.core.TransitionModel;
import com.github.thxmasj.backend.core.internal.TransitionStep.InputStep;
import com.github.thxmasj.backend.core.internal.TransitionStep.OutputStep;
import reactor.core.publisher.Mono;

/// Calculates a transition from an input step, without the state machine storing the result, for tests of the
/// transition models of the other modules of the library. The calculation is not part of the API of [TransitionModel],
/// which registers it here when it is loaded.
public final class Transitions {

  /// The calculation of a transition from an input step
  public interface Calculator {
    <I, O> Mono<OutputStep<O>> calculate(TransitionModel<I, O> transition, InputStep<I> inputStep);
  }

  private static Calculator calculator;

  private Transitions() {}

  /// Registers the calculation, which only [TransitionModel] does
  public static void register(Calculator calculator) {
    if (Transitions.calculator != null)
      throw new IllegalStateException("A calculator is registered already");
    Transitions.calculator = calculator;
  }

  public static <I, O> Mono<OutputStep<O>> calculate(TransitionModel<I, O> transition, InputStep<I> inputStep) {
    return calculator.calculate(transition, inputStep);
  }
}
