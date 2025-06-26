package com.github.thxmasj.statemachine;

import com.github.thxmasj.statemachine.database.Client.ConcurrencyFailure;
import com.github.thxmasj.statemachine.database.mssql.ProcessBackedOff;
import com.github.thxmasj.statemachine.database.mssql.ProcessNew;
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
  private final EntityModel entityModel;
  private final OutboxQueue queue;
  private final String schemaName;
  private final Clock clock;
  private final Listener listener;
  private final ProcessNew processNew;
  private final ProcessBackedOff processBackedOff;

  public enum ExchangeType {
    OutgoingRequest(0),
    IncomingResponse(1),
    OutgoingResponse(6),
    IncomingRequest(7);

    public final int value;

    ExchangeType(int value) {
      this.value = value;
    }

  }

  public enum ForwardStatus {Ok, Dead, Empty, Backoff, Error, Deadlock}

  public OutboxWorker(
      StateMachine stateMachine,
      ProcessNew processNew,
      ProcessBackedOff processBackedOff,
      EntityModel entityModel,
      Listener listener,
      OutboxQueue queue,
      String schemaName,
      Clock clock
  ) {
    this.stateMachine = stateMachine;
    this.entityModel = entityModel;
    this.queue = queue;
    this.schemaName = schemaName;
    this.clock = clock;
    this.listener = listener;
    this.processNew = processNew;
    this.processBackedOff = processBackedOff;
  }

  public Flux<ForwardStatus> doForward() {
    var now = LocalDateTime.now(clock);
//    return processNew.execute(now, entityModel, queue)
//        .onErrorResume(
//            e -> e instanceof PrimaryKeyConstraintViolation f && "OutboxQueueProcessing".equals(f.tableName()),
//            _ -> {
//              listener.forwardingRaced(queue.name());
//              return Mono.empty();
//            }
//        )
//        .mergeWith(processBackedOff.execute(now, queue))
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
        .onErrorResume(this::isDeadlock, _ -> {
          listener.forwardingDeadlock(queue.name());
          return Mono.just(ForwardStatus.Deadlock);
        })
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
