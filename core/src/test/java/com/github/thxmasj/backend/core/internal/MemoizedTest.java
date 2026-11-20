package com.github.thxmasj.backend.core.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

public class MemoizedTest {

  @Test
  public void valueIsCreatedOnce() {
    var created = new AtomicInteger();
    var memoized = new Memoized<>(created::incrementAndGet, "numbers");
    assertThat(memoized.get()).isEqualTo(1);
    assertThat(memoized.get()).isEqualTo(1);
    assertThat(created).hasValue(1);
  }

  /// E.g. the transitions of a model asked for by an inbox its transitions create, which checks them
  @Test
  public void valueAskedForWhileItIsCreatedFails() {
    var self = new AtomicReference<Memoized<String>>();
    self.set(new Memoized<>(() -> self.get().get(), "transitions of Bell"));
    assertThatThrownBy(() -> self.get().get())
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("The transitions of Bell are asked for while they are created");
  }

  @Test
  public void nullValueFails() {
    assertThatThrownBy(() -> new Memoized<>(() -> null, "transitions of Bell").get())
        .isInstanceOf(NullPointerException.class)
        .hasMessage("No transitions of Bell");
  }

}
