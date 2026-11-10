package com.github.thxmasj.backend.core;

import java.util.UUID;

/// A secondary id of an entity. The ids of an entity are the events of a separate id entity with the same entity id,
/// and the id value of an event is unique among the ids of the same model.
public interface SecondaryIdModel<T> {

  /// Type of the id events
  UUID id();

  String name();

  DataType<T> dataType();

  default Group<T> group() {
    return null;
  }

  static <T> SecondaryIdModel<T> of(String name, UUID id, DataType<T> dataType) {
    return of(name, id, dataType, null);
  }

  static <T> SecondaryIdModel<T> of(String name, UUID id, DataType<T> dataType, Group<T> group) {
    return new SecondaryIdModel<>() {
      @Override public UUID id() {return id;}
      @Override public String name() {return name;}
      @Override public DataType<T> dataType() {return dataType;}
      @Override public Group<T> group() {return group;}
      @Override public String toString() {return name;}
    };
  }

  /// Ids of a group are ordered by creation. Membership of a group is an index event on the id entity, keyed by
  /// the group.
  interface Group<T> {
    boolean isInitial(T value);
    SecondaryId<T> initial(Object group);
    SecondaryId<T> next(SecondaryId<T> current);
    Object group(T value);

    /// Data of the index events of a group
    static byte[] key(Object group) {
      return DataType.string().marshal(group.toString());
    }
  }

}
