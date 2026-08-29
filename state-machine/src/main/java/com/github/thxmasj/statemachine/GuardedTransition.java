package com.github.thxmasj.statemachine;

import com.github.thxmasj.statemachine.TransitionModelBuilder.TransitionModel;
import java.util.function.*;

public record GuardedTransition<T, I1, O1>(
    Predicate<T> guard,
    TransitionModelBuilder.TransitionModel<I1, O1> then,
    Function<T, I1> dataAdapter
) {

  public static <T, O1> GuardedTransition<T, T, O1> alternative(Predicate<T> guard, TransitionModel<T, O1> then) {
    return new GuardedTransition<>(guard, then, Function.identity());
  }
}
