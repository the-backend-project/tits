package com.github.thxmasj.statemachine;

public record InputEvent<T>(
    EventType<T, ?> eventType,
    T data
) {}
