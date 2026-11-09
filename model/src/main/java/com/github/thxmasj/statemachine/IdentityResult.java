package com.github.thxmasj.statemachine;

public sealed interface IdentityResult<T> permits IdentityResult.Accepted, IdentityResult.Rejected {

  record Accepted<T>(SecondaryId<T> id) implements IdentityResult<T> {
    public Accepted<T> accepted() {return this;}
  }

  record Rejected<T>(SecondaryId<T> id, EventLog log) implements IdentityResult<T> {
    public Rejected<T> rejected() {return this;}
  }

  static <T> Accepted<T> accepted(SecondaryId<T> id) {
    return new Accepted<>(id);
  }

  static <T> Rejected<T> rejected(SecondaryId<T> id, EventLog log) {
    return new Rejected<>(id, log);
  }

  SecondaryId<T> id();

  default boolean isAccepted() {
    return this instanceof Accepted;
  }

  default Accepted<T> accepted() {
    throw new IllegalStateException("Result is " + this);
  }

  default boolean isRejected() {
    return this instanceof Rejected;
  }

  default Rejected<T> rejected() {
    throw new IllegalStateException("Result is " + this);
  }

}
