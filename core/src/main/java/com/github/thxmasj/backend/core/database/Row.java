package com.github.thxmasj.backend.core.database;

public interface Row {

  <T> T get(String name, Class<T> type);
}
