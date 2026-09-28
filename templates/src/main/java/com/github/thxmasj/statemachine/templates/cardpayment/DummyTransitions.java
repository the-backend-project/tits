package com.github.thxmasj.statemachine.templates.cardpayment;

import static com.github.thxmasj.statemachine.Validated.valid;

import com.github.thxmasj.statemachine.DataType;
import com.github.thxmasj.statemachine.EntityModel;
import com.github.thxmasj.statemachine.http.outbox.AtLeastOnce;
import com.github.thxmasj.statemachine.http.outbox.AtMostOnce;
import com.github.thxmasj.statemachine.http.outbox.HttpOutboxRequest;
import com.github.thxmasj.statemachine.http.outbox.HttpOutboxRequestContract;
import java.time.Duration;
import java.util.UUID;

public class DummyTransitions {

  public static SettlementTransitions settlement() {
    return new SettlementTransitions(
        atLeastOnce("Reconciliation"),
        atLeastOnce("ApprovedCutOff")
    );
  }

  public static PaymentTransitions payment() {
    return new PaymentTransitions(
        null,
        outgoingRequest(),
        null,
        outgoingRequest(),
        outgoingRequest(),
        outgoingRequest(),
        outgoingRequest(),
        outgoingRequest(),
        outgoingRequest(),
        outgoingRequest(),
        outgoingRequest(),
        null,
        outgoingRequest(),
        outgoingRequest(),
        outgoingRequest(),
        outgoingRequest(),
        outgoingRequest(),
        outgoingRequest(),
        outgoingRequest(),
        outgoingRequest(),
        outgoingRequest(),
        outgoingRequest(),
        null
    );
  }

  public static <I, RQ, RS, S> HttpOutboxRequestContract<I, RQ, RS, S> outgoingRequest() {
    return new HttpOutboxRequestContract<>(_ -> null, DataType.json(null), DataType.json(null), (_, _) -> valid(null), _ -> true, _ -> false, _ -> null, _ -> null);
  }

  record Request() {}
  record Response() {}

  public static <I, RI> AtMostOnce<I, RI> atMostOnce(String name) {
    return HttpOutboxRequest.atMostOnce()
        .name(name)
        .id(UUID.randomUUID())
        .requestPayloadType(DataType.json(Request.class))
        .responsePayloadType(DataType.json(Response.class))
        .<I>messageCreator(_ -> null)
        .forwarder(null)
        .processModel(EntityModel.of("Process", UUID.randomUUID()))
        .contentParser(_ -> valid(null))
        .isAccepted(_ -> true)
        .isRejected(_ -> true)
        .isRejectedByInvalidResponse(_ -> true)
        .rollbackModel(DummyTransitions.<RI>atLeastOnce("Rollback" + name))
        .build();
  }

  public static <I> AtLeastOnce<I> atLeastOnce(String name) {
    return HttpOutboxRequest.atLeastOnce()
        .name(name)
        .id(UUID.randomUUID())
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
        .backoffAlgorithm(_ -> Duration.ofSeconds(1))
        .build();
  }

}
