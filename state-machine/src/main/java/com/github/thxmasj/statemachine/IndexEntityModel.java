package com.github.thxmasj.statemachine;

import java.nio.ByteBuffer;
import java.util.UUID;

public interface IndexEntityModel<T> extends EntityModel {

  static IndexEntityModel<UUID> ofUUID(String name, UUID id) {
    return new IndexEntityModel<>() {
      @Override
      public byte[] marshal(UUID value) {
        ByteBuffer bb = ByteBuffer.wrap(new byte[16]);
        bb.putLong(value.getMostSignificantBits());
        bb.putLong(value.getLeastSignificantBits());
        return bb.array();
      }

      @Override
      public UUID unmarshal(byte[] value) {
        ByteBuffer bb = ByteBuffer.wrap(value);
        long high = bb.getLong();
        long low = bb.getLong();
        return new UUID(high, low);      }

      @Override
      public Class<UUID> indexType() {
        return UUID.class;
      }

      @Override public String name() {return name;}
      @Override public UUID id() {return id;}
    };
  }

  byte[] marshal(T value);

  T unmarshal(byte[] value);

  Class<T> indexType();

}
