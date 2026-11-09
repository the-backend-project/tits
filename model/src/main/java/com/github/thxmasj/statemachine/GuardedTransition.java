package com.github.thxmasj.statemachine;

import java.util.function.*;

public record GuardedTransition<T, I1, O1>(
    Predicate<T> guard,
    TransitionModel<I1, O1> then,
    Function<T, I1> dataAdapter
) {}
