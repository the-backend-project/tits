package com.github.thxmasj.statemachine.templates.cardpayment;

import static com.github.thxmasj.statemachine.EntitySelectorBuilder.model;
import static com.github.thxmasj.statemachine.EventTriggerBuilder.event;
import static com.github.thxmasj.statemachine.OutgoingRequestModel.Builder.request;
import static com.github.thxmasj.statemachine.TransitionModel.Builder.from;
import static com.github.thxmasj.statemachine.templates.cardpayment.Identifiers.AcquirerBatchNumber;
import static com.github.thxmasj.statemachine.templates.cardpayment.Identifiers.BatchNumber;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentState.Begin;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentState.Error;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentState.ProcessingSettlement;
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
import static com.github.thxmasj.statemachine.templates.cardpayment.Queues.Acquirer;
import static com.github.thxmasj.statemachine.templates.cardpayment.Queues.Merchant;

import com.github.thxmasj.statemachine.EntityModel;
import com.github.thxmasj.statemachine.EventType;
import com.github.thxmasj.statemachine.IncomingResponseValidator;
import com.github.thxmasj.statemachine.State;
import com.github.thxmasj.statemachine.OutboxQueue;
import com.github.thxmasj.statemachine.TransitionModel;
import com.github.thxmasj.statemachine.database.mssql.SchemaNames;
import com.github.thxmasj.statemachine.message.http.Created;
import java.util.List;
import java.util.UUID;
import reactor.core.publisher.Mono;

public abstract class AbstractSettlement implements EntityModel {

  private final UUID id = UUID.fromString("fc1d7b4c-92c7-40b3-9f7e-68a84d8d56f1");

  @Override
  public UUID id() {
    return id;
  }

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
        from(Begin).toSelf().onEvent(Open).build(),
        from(Begin).toSelf().onEvent(MerchantCredit).build(),
        from(Begin).toSelf().onEvent(MerchantDebit).build(),
        from(Begin).toSelf().onEvent(MerchantCreditReversed).build(),
        from(Begin).toSelf().onEvent(MerchantDebitReversed).build(),
        from(Begin).to(ProcessingSettlement).onEvent(CutOffRequest)
            .withData(_ -> Mono.just(""))
            .response(new Created())
            .trigger(_ -> event(Open).onEntity(this)
                .identifiedBy(model(BatchNumber).next().create())
                .and(model(AcquirerBatchNumber).next().create())
            )
            .notify(request(reconciliation()).to(Acquirer).guaranteed().responseValidator(validateSettlementResponse())),
        // For previous batch to stay open for ongoing capture exchanges when cut-off is performed
        from(ProcessingSettlement).toSelf().onEvent(MerchantCredit).build(),
        from(ProcessingSettlement).toSelf().onEvent(MerchantDebit).build(),
        from(ProcessingSettlement).toSelf().onEvent(MerchantCreditReversed).build(),
        from(ProcessingSettlement).toSelf().onEvent(MerchantDebitReversed).build(),
        from(ProcessingSettlement).to(Settled).onEvent(SettlementApproved).build(),
        from(ProcessingSettlement).to(Error).onEvent(Timeout).build(),
        from(Settled).to(Reconciled).onEvent(InBalance)
            .withData(new ApprovedCutOffDataCreator())
            .notify(request(approvedCutOff()).to(Merchant).guaranteed()),
        from(Settled).to(Error).onEvent(OutOfBalance).build()
    );
  }

  @Override
  public List<OutboxQueue> queues() {
    return List.of(Queues.values());
  }

}
