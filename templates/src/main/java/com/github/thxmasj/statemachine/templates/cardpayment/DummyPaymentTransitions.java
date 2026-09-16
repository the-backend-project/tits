package com.github.thxmasj.statemachine.templates.cardpayment;

import static com.github.thxmasj.statemachine.Validated.valid;

import com.github.thxmasj.statemachine.http.outbox.AtLeastOnce;
import com.github.thxmasj.statemachine.http.outbox.AtMostOnce;
import com.github.thxmasj.statemachine.http.outbox.HttpOutboxRequest;
import java.time.Duration;
import java.util.UUID;

public class DummyPaymentTransitions {

  public static PaymentTransitions build() {
    return new PaymentTransitions(
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
    );
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
        .rollbackModel(DummyPaymentTransitions.<RI>atLeastOnce("Rollback" + name))
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
