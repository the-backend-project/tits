package com.github.thxmasj.backend.http.outbox;

import com.github.thxmasj.backend.core.*;

import java.util.function.*;

public record Callback<T, U>(
    EventType<U, ?> eventType,
    Function<T, U> dataAdapter
) {}
