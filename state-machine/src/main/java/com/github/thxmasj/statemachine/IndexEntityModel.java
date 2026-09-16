package com.github.thxmasj.statemachine;

import java.util.UUID;

public interface IndexEntityModel<T> extends EntityModel {

  static IndexEntityModel<UUID> ofUUID(String name, UUID id) {
    return new IndexEntityModel<>() {
      @Override
      public String marshal(UUID value) {
        return value.toString();
      }

      @Override
      public UUID unmarshal(String value) {
        return UUID.fromString(value);
      }

      @Override
      public Class<UUID> indexType() {
        return UUID.class;
      }

      @Override public String name() {return name;}
      @Override public UUID id() {return id;}
    };
  }

  String marshal(T value);

  T unmarshal(String value);

  Class<T> indexType();

}
