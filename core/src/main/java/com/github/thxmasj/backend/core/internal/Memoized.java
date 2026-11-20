package com.github.thxmasj.backend.core.internal;

import static java.util.Objects.requireNonNull;

import java.util.function.Supplier;

/// A value created by the supplier when it is first asked for, and kept, e.g. the transitions of an entity model. The
/// supplier is let go of once the value is created, with what it refers to.
public final class Memoized<T> implements Supplier<T> {

  private final String description;
  private Supplier<T> supplier;
  private boolean creating;
  private volatile T value;

  /// @param description what the value is, for the messages if it can't be created
  public Memoized(Supplier<T> supplier, String description) {
    this.supplier = requireNonNull(supplier);
    this.description = description;
  }

  /// @throws IllegalStateException if the value is asked for while it is created, e.g. by the supplier itself, which
  ///                               would otherwise recurse for good
  /// @throws NullPointerException if the supplier gives null
  @Override
  public T get() {
    T result = value;
    if (result != null)
      return result;
    synchronized (this) {
      if (value != null)
        return value;
      if (creating)
        throw new IllegalStateException("The " + description + " are asked for while they are created");
      creating = true;
      try {
        value = requireNonNull(supplier.get(), () -> "No " + description);
        supplier = null;
        return value;
      } finally {
        creating = false;
      }
    }
  }
}
