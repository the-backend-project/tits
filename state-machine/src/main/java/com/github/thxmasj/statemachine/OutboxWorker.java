package com.github.thxmasj.statemachine;

import com.github.thxmasj.statemachine.database.Client.ConcurrencyFailure;
import com.github.thxmasj.statemachine.database.Client.PrimaryKeyConstraintViolation;
import com.github.thxmasj.statemachine.database.mssql.ProcessBackedOff;
import com.github.thxmasj.statemachine.database.mssql.ProcessNew;
import com.github.thxmasj.statemachine.database.mssql.SchemaNames;
import com.github.thxmasj.statemachine.message.http.HttpRequestMessage;
import com.github.thxmasj.statemachine.message.http.HttpResponseMessage;
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
  private final Subscriber subscriber;
  private final String schemaName;
  private final Clock clock;
  private final Listener listener;
  private final ProcessNew processNew;
  private final ProcessBackedOff processBackedOff;

  public enum ExchangeType {
    OutgoingRequest(0),
    IncomingResponse(1),
    ReverseOutgoingRequest(2),
    OutgoingResponse(6),
    IncomingRequest(7);

    public final int value;

    ExchangeType(int value) {
      this.value = value;
    }

  }

  public enum ForwardStatus {Ok, Dead, Empty, Backoff, Error, Deadlock}

  public interface ResponseEvaluator {

    enum Status {Ok, PermanentError, TransientError}

    record EvaluatedResponse(Status status, StatusReason statusReason, String message) {}

    interface StatusReason {

      String message();
    }

    record HttpStatusReason(int status, String reasonPhrase) implements StatusReason {

      @Override
      public String message() {
        return status + " " + reasonPhrase;
      }
    }

    default EvaluatedResponse evaluate(
        HttpRequestMessage ignoredRequestMessage,
        HttpResponseMessage responseMessage
    ) {
      int c = responseMessage.statusCode();
      Status status;
      if (c >= 200 && c < 300) {
        status = ResponseEvaluator.Status.Ok;
      } else if (c >= 400 && c < 500) {
        status = ResponseEvaluator.Status.PermanentError;
      } else if (c >= 500 && c < 600) {
        status = ResponseEvaluator.Status.TransientError;
      } else {
        status = ResponseEvaluator.Status.PermanentError;
      }
      return new EvaluatedResponse(status,
          new HttpStatusReason(responseMessage.statusCode(), responseMessage.reasonPhrase()),
          responseMessage.message()
      );
    }
  }

  public OutboxWorker(
      StateMachine stateMachine,
      ProcessNew processNew,
      ProcessBackedOff processBackedOff,
      EntityModel entityModel,
      Listener listener,
      Subscriber subscriber,
      String schemaName,
      Clock clock
  ) {
    this.stateMachine = stateMachine;
    this.entityModel = entityModel;
    this.subscriber = subscriber;
    this.schemaName = schemaName;
    this.clock = clock;
    this.listener = listener;
    this.processNew = processNew;
    this.processBackedOff = processBackedOff;
  }

  public Flux<ForwardStatus> doForward() {
    var now = LocalDateTime.now(clock);
    return processNew.execute(now, entityModel, subscriber)
        .onErrorResume(
            e -> e instanceof PrimaryKeyConstraintViolation f && new SchemaNames(schemaName, entityModel).processingTableName(subscriber).equals(f.tableName()),
            _ -> {
              listener.forwardingRaced(subscriber.name());
              return Mono.empty();
            }
        )
        .mergeWith(processBackedOff.execute(now, entityModel, subscriber))
        .doOnNext(e -> listener.forwardingAttempt(e.subscriber().name(), e.enqueuedAt(), e.attempt(), e.entityId(), e.eventNumber(), e.correlationId()))
        .flatMap(stateMachine::doForward)
        .onErrorResume(this::isDeadlock, _ -> {
          listener.forwardingDeadlock(subscriber.name());
          return Mono.just(ForwardStatus.Deadlock);
        })
        .onErrorResume(t -> {
          listener.forwardingError(subscriber.name(), t);
          return Mono.just(ForwardStatus.Error);
        })
        .switchIfEmpty(Flux.just(ForwardStatus.Empty).doOnNext(_ -> listener.forwardingEmptyQueue(subscriber.name())));
  }

  public Looper<ForwardStatus> forwarder(boolean enable) {
    return new Looper<>("MessageForwarder-Looper-" + subscriber, !enable, this::doForward, status -> switch (status) {
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
