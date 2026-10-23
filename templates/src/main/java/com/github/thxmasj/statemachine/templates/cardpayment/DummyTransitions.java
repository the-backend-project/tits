package com.github.thxmasj.statemachine.templates.cardpayment;

import static com.github.thxmasj.statemachine.Validated.valid;

import com.github.thxmasj.statemachine.DataType;
import com.github.thxmasj.statemachine.EntityModel;
import com.github.thxmasj.statemachine.http.outbox.AtLeastOnceBuilder;
import com.github.thxmasj.statemachine.http.outbox.AtMostOnceBuilder;
import com.github.thxmasj.statemachine.http.outbox.HttpOutboxRequest;
import java.time.Duration;
import java.util.UUID;

public class DummyTransitions {

  public static SettlementTransitions settlement() {
    return new SettlementTransitions(
        atLeastOnce(),
        atLeastOnce()
    );
  }

  public static PaymentTransitions payment() {
    return new PaymentTransitions(
        null,
        atMostOnce(),
        atMostOnce(),
        atLeastOnce(),
        atMostOnce(),
        atLeastOnce(),
        atLeastOnce(),
        atMostOnce(),
        atLeastOnce(),
        atLeastOnce(),
        atLeastOnce(),
        atLeastOnce(),
        atLeastOnce(),
        atLeastOnce(),
        atLeastOnce(),
        atLeastOnce(),
        atLeastOnce(),
        atLeastOnce()
    );
  }

  record Request() {}
  record Response() {}

  public static <I, RI> AtMostOnceBuilder.BuildStep<I, RI> atMostOnce() {
    return HttpOutboxRequest.atMostOnce()
        .requestPayloadType(DataType.json(Request.class))
        .responsePayloadType(DataType.json(Response.class))
        .<I>messageCreator(_ -> null)
        .forwarder(null)
        .processModel(EntityModel.of("Process", UUID.randomUUID()))
        .contentParser(_ -> valid(null))
        .isAccepted(_ -> true)
        .isRejected(_ -> true)
        .isRejectedByInvalidResponse(_ -> true)
        .rollbackModel(atLeastOnce());
  }

  public static <I> AtLeastOnceBuilder.BuildStep<I> atLeastOnce() {
    return HttpOutboxRequest.atLeastOnce()
        .requestPayloadType(DataType.json(Request.class))
        .responsePayloadType(DataType.json(Response.class))
        .<I>messageCreator(_ -> null)
        .forwarder(null)
        .processModel(EntityModel.of("Process", UUID.randomUUID()))
        .contentParser(_ -> valid(null))
        .isAccepted(_ -> true)
        .isFailureTransient(_ -> true)
        .isRejectedByInvalidResponse(_ -> true)
        .isFailureByInvalidResponseTransient(_ -> true)
        .isAttemptAvailable(_ -> true)
        .backoffAlgorithm(_ -> Duration.ofSeconds(1));
  }

}
