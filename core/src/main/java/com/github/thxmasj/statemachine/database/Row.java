package com.github.thxmasj.statemachine.database;

public interface Row {

  <T> T get(String name, Class<T> type);
}
