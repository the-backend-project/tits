package com.github.thxmasj.statemachine;

import java.util.UUID;

public interface Identifiable {

  String name();

  UUID id();

  static Identifiable of(String name, UUID id) {
    return new Identifiable() {
      @Override
      public String name() {
        return name;
      }
      @Override
      public UUID id() {
        return id;
      }
    };
  }

}
