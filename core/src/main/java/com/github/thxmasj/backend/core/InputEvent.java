package com.github.thxmasj.backend.core;

public record InputEvent<T>(
    EventType<T, ?> eventType,
    T data
) {}
