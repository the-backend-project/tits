package com.github.thxmasj.statemachine.templates;

import static com.github.thxmasj.statemachine.Validated.valid;
import static com.github.thxmasj.statemachine.http.inbox.HttpInbox.EntityModels.RequestDispatching;
import static com.github.thxmasj.statemachine.http.inbox.HttpInbox.EntityModels.RequestRouting;
import static com.github.thxmasj.statemachine.http.inbox.TransitionModels.requestDispatchingTransitions;
import static com.github.thxmasj.statemachine.http.inbox.TransitionModels.requestRoutingTransitions;
import static com.github.thxmasj.statemachine.templates.Batching.EntityTypes.Batch;
import static com.github.thxmasj.statemachine.templates.Batching.EntityTypes.Item;
import static com.github.thxmasj.statemachine.templates.cardpayment.Aggregate.Payment;
import static com.github.thxmasj.statemachine.templates.cardpayment.Aggregate.Settlement;

import com.github.thxmasj.statemachine.PlantUMLFormatter;
import com.github.thxmasj.statemachine.http.outbox.AtLeastOnce;
import com.github.thxmasj.statemachine.http.outbox.AtMostOnce;
import com.github.thxmasj.statemachine.http.outbox.HttpOutboxRequest;
import com.github.thxmasj.statemachine.templates.cardpayment.PaymentTransitions;
import com.github.thxmasj.statemachine.templates.cardpayment.SettlementTransitions;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

public class Main {

  static void main() throws IOException {
    System.out.println(new PlantUMLFormatter(RequestRouting, requestRoutingTransitions(List.of())).formatToImage("docs/images/"));
    System.out.println(new PlantUMLFormatter(RequestDispatching, requestDispatchingTransitions(List.of(), List.of())).formatToImage("docs/images/"));
    System.out.println(new PlantUMLFormatter(Item, Item.transitions()).formatToImage("docs/images/"));
    System.out.println(new PlantUMLFormatter(Batch, Batch.transitions()).formatToImage("docs/images/"));
    System.out.println(new PlantUMLFormatter(
        Payment,
        new PaymentTransitions(
            null,
            atMostOnce("Authentication"),
            atLeastOnce("FailedAuthenticationToAcquirer"),
            atMostOnce("AuthorisationToAcquirer"),
            atMostOnce("PreauthorisationToAcquirer"),
            atLeastOnce("CaptureRequestedTooLateToAcquirer"),
            atLeastOnce("CaptureToAcquirer"),
            atMostOnce("RefundAuthorisationToAcquirer"),
            atLeastOnce("RolledBackAuthorisationRequestToMerchant"),
            atLeastOnce("RolledBackPreauthorisationRequestToMerchant"),
            atLeastOnce("ApprovedPreauthorisationToMerchant"),
            atLeastOnce("FailedAuthorisationToMerchant"),
            atLeastOnce("ApprovedAuthorisationToMerchant"),
            atLeastOnce("DeclinedAuthorisationToMerchant"),
            atLeastOnce("ApprovedCaptureToMerchant"),
            atLeastOnce("FailedRefundToMerchant"),
            atLeastOnce("ApprovedRefundToMerchant"),
            atLeastOnce("DeclinedRefundToMerchant")
        ).transitions()
    ).formatToImage("docs/images/"));
    System.out.println(new PlantUMLFormatter(
        Settlement,
        new SettlementTransitions(
            atLeastOnce("ReconciliationToAcquirer"),
            atLeastOnce("approvedCutOffToMerchant")
        ).transitions()
    ).formatToImage("docs/images/"));
  }

  private static <I, RI> AtMostOnce<I, RI> atMostOnce(String name) {
    return HttpOutboxRequest.atMostOnce()
        .name(name)
        .id(UUID.randomUUID())
        .<I>messageCreator(_ -> null)
        .forwarder(null)
        .contentParser(_ -> valid(null))
        .isDelivered(_ -> true)
        .isRejectedByInvalidResponse(_ -> true)
        .rollbackModel(Main.<RI>atLeastOnce("Rollback" + name))
        .build();
  }

  private static <I> AtLeastOnce<I> atLeastOnce(String name) {
    return HttpOutboxRequest.atLeastOnce()
        .name(name)
        .id(UUID.randomUUID())
        .<I>messageCreator(_ -> null)
        .forwarder(null)
        .contentParser(_ -> valid(null))
        .isDelivered(_ -> true)
        .isFailureTransient(_ -> true)
        .isRejectedByInvalidResponse(_ -> true)
        .isFailureByInvalidResponseTransient(_ -> true)
        .isAttemptAvailable(_ -> true)
        .backoffAlgorithm(_ -> Duration.ofSeconds(1))
        .build();
  }

}
