package com.github.thxmasj.backend.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public class LooperTest {

  @Test
  public void stopCutsDelayShort() throws InterruptedException {
    var iterations = new AtomicInteger();
    var looper = new Looper<>("Test", false, () -> Flux.just(iterations.incrementAndGet()), _ -> Duration.ofHours(1));
    looper.loop();
    while (iterations.get() == 0)
      Thread.onSpinWait();
    looper.stop();
    assertThat(looper.awaitStopped(Duration.ofSeconds(5))).isTrue();
    assertThat(iterations.get()).isEqualTo(1);
  }

  @Test
  public void stopWaitsForOngoingIteration() throws InterruptedException {
    var started = new CountDownLatch(1);
    var completed = new AtomicBoolean();
    var looper = new Looper<>(
        "Test",
        false,
        () -> Mono.fromRunnable(started::countDown)
            .then(Mono.delay(Duration.ofMillis(500)))
            .doOnNext(_ -> completed.set(true))
            .flux(),
        _ -> Duration.ofHours(1)
    );
    looper.loop();
    assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
    looper.stop();
    assertThat(looper.awaitStopped(Duration.ofSeconds(5))).isTrue();
    assertThat(completed).isTrue();
  }

}
