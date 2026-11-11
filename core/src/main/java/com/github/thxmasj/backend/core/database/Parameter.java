package com.github.thxmasj.backend.core.database;

public record Parameter<T>(Class<? extends T> type, T value) {}
