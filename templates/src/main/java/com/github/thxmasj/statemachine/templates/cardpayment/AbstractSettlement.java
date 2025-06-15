package com.github.thxmasj.statemachine.templates.cardpayment;

import static com.github.thxmasj.statemachine.EntitySelectorBuilder.model;
import static com.github.thxmasj.statemachine.EventTriggerBuilder.event;
import static com.github.thxmasj.statemachine.OutgoingRequestModel.Builder.request;
import static com.github.thxmasj.statemachine.TransitionModel.Builder.from;
import static com.github.thxmasj.statemachine.templates.cardpayment.Identifiers.AcquirerBatchNumber;
import static com.github.thxmasj.statemachine.templates.cardpayment.Identifiers.BatchNumber;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentState.Begin;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentState.Error;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentState.PendingSettlement;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentState.Reconciled;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentState.Settled;
import static com.github.thxmasj.statemachine.templates.cardpayment.SettlementEvent.Type.CutOffRequest;
import static com.github.thxmasj.statemachine.templates.cardpayment.SettlementEvent.Type.InBalance;
import static com.github.thxmasj.statemachine.templates.cardpayment.SettlementEvent.Type.MerchantCredit;
import static com.github.thxmasj.statemachine.templates.cardpayment.SettlementEvent.Type.MerchantCreditReversed;
import static com.github.thxmasj.statemachine.templates.cardpayment.SettlementEvent.Type.MerchantDebit;
import static com.github.thxmasj.statemachine.templates.cardpayment.SettlementEvent.Type.MerchantDebitReversed;
import static com.github.thxmasj.statemachine.templates.cardpayment.SettlementEvent.Type.Open;
import static com.github.thxmasj.statemachine.templates.cardpayment.SettlementEvent.Type.OutOfBalance;
import static com.github.thxmasj.statemachine.templates.cardpayment.SettlementEvent.Type.SettlementApproved;
import static com.github.thxmasj.statemachine.templates.cardpayment.SettlementEvent.Type.Timeout;
import static com.github.thxmasj.statemachine.templates.cardpayment.Subscribers.Acquirer;
import static com.github.thxmasj.statemachine.templates.cardpayment.Subscribers.Merchant;

import com.github.thxmasj.statemachine.EntityModel;
import com.github.thxmasj.statemachine.EventType;
import com.github.thxmasj.statemachine.IncomingResponseValidator;
import com.github.thxmasj.statemachine.State;
import com.github.thxmasj.statemachine.Subscriber;
import com.github.thxmasj.statemachine.TransitionModel;
import com.github.thxmasj.statemachine.database.mssql.SchemaNames;
import com.github.thxmasj.statemachine.message.http.Created;
import java.util.List;
import reactor.core.publisher.Mono;

public abstract class AbstractSettlement implements EntityModel {

  @Override
  public String name() {
    return "Settlement";
  }

  @Override
  public List<SchemaNames.SecondaryIdModel> secondaryIds() {
    return List.of(Identifiers.values());
  }

  @Override
  public List<EventType> eventTypes() {
    return List.of(SettlementEvent.Type.values());
  }

  @Override
  public State initialState() {
    return Begin;
  }

  protected abstract IncomingResponseValidator<AcquirerResponse> validateSettlementResponse();

  protected abstract OutgoingRequests.Reconciliation reconciliation();

  protected abstract OutgoingRequests.ApprovedCutOff approvedCutOff();

  @Override
  public List<TransitionModel<?>> transitions() {
    return List.of(
        from(Begin).to(Begin).onEvent(Open).build(),
        from(Begin).to(Begin).onEvent(MerchantCredit).build(),
        from(Begin).to(Begin).onEvent(MerchantDebit).build(),
        from(Begin).to(Begin).onEvent(MerchantCreditReversed).build(),
        from(Begin).to(Begin).onEvent(MerchantDebitReversed).build(),
        from(Begin).to(PendingSettlement).onEvent(CutOffRequest)
            .withData(_ -> Mono.just(""))
            .response(_ -> "", new Created())
            .trigger(_ -> event(Open).onEntity(this)
                .identifiedBy(model(BatchNumber).next().create())
                .and(model(AcquirerBatchNumber).next().create())
            )
            .notify(request(reconciliation()).to(Acquirer).guaranteed().responseValidator(validateSettlementResponse())),
        // To support "Previous batch is kept open after cut-off"
        from(PendingSettlement).to(PendingSettlement).onEvent(MerchantCredit).build(),
        from(PendingSettlement).to(PendingSettlement).onEvent(MerchantDebit).build(),
        from(PendingSettlement).to(PendingSettlement).onEvent(MerchantCreditReversed).build(),
        from(PendingSettlement).to(PendingSettlement).onEvent(MerchantDebitReversed).build(),
        from(PendingSettlement).to(Settled).onEvent(SettlementApproved).build(),
        from(PendingSettlement).to(Error).onEvent(Timeout).build(),
        from(Settled).to(Reconciled).onEvent(InBalance)
            .withData(_ -> Mono.just(""))
            .notify(request(approvedCutOff()).to(Merchant).guaranteed()),
        from(Settled).to(Error).onEvent(OutOfBalance).build()
    );
  }

  @Override
  public List<Subscriber> subscribers() {
    return List.of(Subscribers.values());
  }

}
