package com.github.thxmasj.statemachine.templates.cardpayment;

import com.github.thxmasj.statemachine.BuiltinEntities.InboxExchange;
import com.github.thxmasj.statemachine.IncomingResponseValidator;
import com.github.thxmasj.statemachine.TransitionModelBuilder.TransitionModel;
import com.github.thxmasj.statemachine.TransitionModelBuilder.WithFilter.Alternative;
import com.github.thxmasj.statemachine.message.http.HttpRequestMessage;
import com.github.thxmasj.statemachine.templates.cardpayment.OutgoingRequests.ApprovedCutOff;
import com.github.thxmasj.statemachine.templates.cardpayment.OutgoingRequests.Reconciliation;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

public class DummySettlementTransitions extends SettlementTransitions {

  public DummySettlementTransitions() {
    super(new InboxExchange() {
      @Override
      public Map<Predicate<HttpRequestMessage>, Alternative<HttpRequestMessage, ?, ?>> routes() {
        return Map.of();
      }

      @Override
      protected List<TransitionModel<?, ?>> responseTransitions() {
        return List.of();
      }
    });
  }

  @Override
  protected IncomingResponseValidator<AcquirerResponse> validateSettlementResponse() {
    return null;
  }

  @Override
  protected OutgoingRequests.Reconciliation reconciliation() {
    return new Reconciliation() {};
  }

  @Override
  protected OutgoingRequests.ApprovedCutOff approvedCutOff() {
    return new ApprovedCutOff() {};
  }
}
