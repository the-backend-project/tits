package com.github.thxmasj.statemachine.http.outbox;

import com.github.thxmasj.statemachine.*;

import java.util.function.*;

public record Callback<T, U>(
    EventType<U, ?> eventType,
    Function<T, U> dataAdapter
) {}
