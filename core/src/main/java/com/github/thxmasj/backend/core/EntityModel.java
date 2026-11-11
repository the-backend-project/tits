package com.github.thxmasj.backend.core;

import java.util.UUID;

public interface EntityModel extends Identifiable {

  State Begin = () -> "Begin";

  static EntityModel of(String name, UUID id) {
    return new EntityModel() {
      @Override public String name() {return name;}
      @Override public UUID id() {return id;}
      @Override public String toString() {return name;}
    };
  }
}
