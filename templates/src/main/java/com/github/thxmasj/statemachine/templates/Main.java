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
    System.out.println(new PlantUMLFormatter(Payment,
        new PaymentTransitions(
            null,
            atMostOnce("Authentication", UUID.randomUUID(), null),
            atLeastOnce("FailedAuthenticationToAcquirer", UUID.randomUUID(), null),
            atLeastOnce("FailedTokenValidationToAcquirer", UUID.randomUUID(), null),
            atMostOnce("AuthorisationToAcquirer", UUID.randomUUID(), null),
            atMostOnce("PreauthorisationToAcquirer", UUID.randomUUID(), null),
            atLeastOnce("CaptureRequestedTooLateToAcquirer", UUID.randomUUID(), null),
            atLeastOnce("CaptureToAcquirer", UUID.randomUUID(), null),
            atMostOnce("RefundAuthorisationToAcquirer", UUID.randomUUID(), null),
            atLeastOnce("RolledBackAuthorisationRequestToMerchant", UUID.randomUUID(), null),
            atLeastOnce("RolledBackPreauthorisationRequestToMerchant", UUID.randomUUID(), null),
            atLeastOnce("ApprovedPreauthorisationToMerchant", UUID.randomUUID(), null),
            atLeastOnce("FailedAuthorisationToMerchant", UUID.randomUUID(), null),
            atLeastOnce("ApprovedAuthorisationToMerchant", UUID.randomUUID(), null),
            atLeastOnce("DeclinedAuthorisationToMerchant", UUID.randomUUID(), null),
            atLeastOnce("ApprovedCaptureToMerchant", UUID.randomUUID(), null),
            atLeastOnce("FailedRefundToMerchant", UUID.randomUUID(), null),
            atLeastOnce("ApprovedRefundToMerchant", UUID.randomUUID(), null),
            atLeastOnce("DeclinedRefundToMerchant", UUID.randomUUID(), null)
        ).transitions()
    ).formatToImage("docs/images/"));
    System.out.println(new PlantUMLFormatter(
        Settlement,
        new SettlementTransitions(
            atLeastOnce("ReconciliationToAcquirer", UUID.randomUUID(), null),
            atLeastOnce("approvedCutOffToMerchant", UUID.randomUUID(), null)
        ).transitions()
    ).formatToImage("docs/images/"));
  }

  private static <I, R> AtMostOnce<I, R> atMostOnce(String name, UUID id, Class<I> inputDataType) {
    return new AtMostOnce<>(
        name,
        id,
        inputDataType,
        _ -> null,
        null,
        Duration.ofSeconds(10),
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        _ -> valid(null),
        _ -> true,
        _ -> true,
        atLeastOnce("Rollback" + name, id, null)
    );
  }

  private static <I> AtLeastOnce<I> atLeastOnce(String name, UUID id, Class<I> inputDataType) {
    return new AtLeastOnce<I>(
        name,
        id,
        inputDataType,
        _ -> null,
        (_, m) -> m,
        null,
        Duration.ofSeconds(10),
        null,
        null,
        _ -> valid(null),
        _ -> true,
        _ -> true,
        _ -> true,
        _ -> true,
        _ -> true,
        _ -> Duration.ofSeconds(1)
    );
  }

}
