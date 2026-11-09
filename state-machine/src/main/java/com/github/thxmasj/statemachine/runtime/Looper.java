package com.github.thxmasj.statemachine.runtime;

import java.time.Duration;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.function.Supplier;
import reactor.core.publisher.Flux;

public class Looper<STATUS> {

  private final String name;
  private final Supplier<Flux<STATUS>> runnable;
  private final Function<STATUS, Duration> delayStrategy;
  private final ReentrantLock lock = new ReentrantLock();
  private final Condition stopSignal = lock.newCondition();
  private volatile boolean stopped;
  private Thread thread;

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

  /// Stops looping after the ongoing iteration. A delay between iterations is cut short.
  public void stop() {
    lock.lock();
    try {
      stopped = true;
      stopSignal.signalAll();
    } finally {
      lock.unlock();
    }
  }

  /// Waits for the loop to stop.
  ///
  /// @return `true` if the loop has stopped, `false` if it did not stop within the timeout
  public boolean awaitStopped(Duration timeout) throws InterruptedException {
    return thread == null || thread.join(timeout);
  }

  public void loop() {
    stopped = false;
    thread = new Thread(
        () -> {
          while (!stopped) {
            STATUS status = runnable.get().blockLast();
            await(status != null ? delayStrategy.apply(status) : Duration.ofSeconds(10));
          }
        },
        name
    );
    thread.start();
  }

  private void await(Duration delay) {
    lock.lock();
    try {
      long nanos = delay.toNanos();
      while (!stopped && nanos > 0)
        nanos = stopSignal.awaitNanos(nanos);
    } catch (InterruptedException _) {
      stopped = true;
    } finally {
      lock.unlock();
    }
  }

}
