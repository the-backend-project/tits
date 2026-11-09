package com.github.thxmasj.statemachine.database;

public record Parameter<T>(Class<? extends T> type, T value) {}
