package com.github.thxmasj.backend.core.internal;

import static java.util.Objects.requireNonNull;

import java.util.function.Supplier;

/// A value created by the supplier when it is first asked for, and kept, e.g. the transitions of an entity model
public final class Memoized<T> implements Supplier<T> {

  private final Supplier<T> supplier;
  private final String description;
  private volatile T value;

  /// @param description what the value is, for the message if the supplier gives null
  public Memoized(Supplier<T> supplier, String description) {
    this.supplier = requireNonNull(supplier);
    this.description = description;
  }

  @Override
  public T get() {
    T result = value;
    if (result == null) {
      synchronized (this) {
        result = value;
        if (result == null)
          value = result = requireNonNull(supplier.get(), () -> "No " + description);
      }
    }
    return result;
  }
}
