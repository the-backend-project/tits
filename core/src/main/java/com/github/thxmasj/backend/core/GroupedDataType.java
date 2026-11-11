package com.github.thxmasj.backend.core;

/// Marshals the values of a group to the marshalled group followed by bytes ordering the values of the group, so that
/// the values of a group are found and ordered by comparing them as unsigned bytes, e.g. in a database index
public interface GroupedDataType<T, G> extends DataType<T> {

  /// The prefix of the marshalled values of the group, which no other group's prefix may start with
  byte[] marshalGroup(G group);

}
