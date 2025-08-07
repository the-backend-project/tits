package com.github.thxmasj.statemachine;

import com.github.thxmasj.statemachine.database.Client.ConcurrencyFailure;
import com.github.thxmasj.statemachine.database.mssql.ProcessBackedOff;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public class OutboxWorker {

  private static final Pattern deadlockPattern = Pattern.compile(
      "Transaction \\(Process ID (\\d+)\\) was deadlocked on lock resources with another process and has been chosen as the deadlock victim. Rerun the transaction.");

  private final StateMachine stateMachine;
  private final OutboxQueue queue;
  private final Clock clock;
  private final Listener listener;
  private final ProcessBackedOff processBackedOff;

  public enum ForwardStatus {Ok, Dead, Empty, Backoff, Error, Deadlock}

  public OutboxWorker(
      StateMachine stateMachine,
      ProcessBackedOff processBackedOff,
      Listener listener,
      OutboxQueue queue,
      Clock clock
  ) {
    this.stateMachine = stateMachine;
    this.queue = queue;
    this.clock = clock;
    this.listener = listener;
    this.processBackedOff = processBackedOff;
  }

  public Flux<ForwardStatus> doForward() {
    var now = LocalDateTime.now(clock);
    return processBackedOff.execute(now, queue)
        .doOnNext(e -> listener.forwardingAttempt(
                e.requestId(),
                e.queue().name(),
                e.enqueuedAt(),
                e.attempt(),
                e.entityId(),
                e.eventNumber(),
                e.correlationId()
            )
        )
        .flatMap(stateMachine::forward)
        .onErrorResume(
            this::isDeadlock, _ -> {
              listener.forwardingDeadlock(queue.name());
              return Mono.just(ForwardStatus.Deadlock);
            }
        )
        .onErrorResume(t -> {
          listener.forwardingError(queue.name(), t);
          return Mono.just(ForwardStatus.Error);
        })
        .switchIfEmpty(Flux.just(ForwardStatus.Empty).doOnNext(_ -> listener.forwardingEmptyQueue(queue.name())));
  }

  public Looper<ForwardStatus> forwarder(boolean enable) {
    return new Looper<>("OutboxWorker-Looper-" + queue, !enable, this::doForward, status -> switch (status) {
      case Ok, Backoff, Dead, Deadlock -> Duration.ZERO;
      case Empty, Error -> Duration.ofSeconds(1);
    });
  }

  private boolean isDeadlock(Throwable e) {
    if (e instanceof ConcurrencyFailure f) {
      Matcher matcher = deadlockPattern.matcher(f.getMessage());
      return matcher.matches();
    }
    return false;
  }

  public enum Simulation {Race}

}
