package com.github.thxmasj.statemachine;

public record InputEvent<T>(
    EventType eventType,
    T data,
    String errorMessage
) {}
