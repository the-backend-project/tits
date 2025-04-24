package com.github.thxmasj.statemachine;

import java.time.Duration;
import java.util.function.Function;
import java.util.function.Supplier;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public class Looper<STATUS> {

  private final String name;
  private final Supplier<Flux<STATUS>> runnable;
  private final Function<STATUS, Duration> delayStrategy;
  private boolean stopped;

  public Looper(
      String name,
      boolean stopped,
      Supplier<Flux<STATUS>> runnable,
      Function<STATUS, Duration> delayStrategy
  ) {
    this.name = name;
    this.stopped = stopped;
    this.runnable = runnable;
    this.delayStrategy = delayStrategy;
  }

  public void stop() {
    stopped = true;
  }

  public void loop() {
    stopped = false;
    new Thread(
        () -> {
          while (!stopped) {
            runnable.get()
                .flatMap(status -> Mono.just("").delayElement(delayStrategy.apply(status)))
                .switchIfEmpty(Mono.just("").delayElement(Duration.ofSeconds(10)))
                .blockLast();
          }
        },
        name
    ).start();
  }

}
