package com.github.thxmasj.statemachine.templates.cardpayment;

import static com.github.thxmasj.statemachine.EntitySelectorBuilder.model;
import static com.github.thxmasj.statemachine.EventTriggerBuilder.event;
import static com.github.thxmasj.statemachine.OutgoingRequestModel.Builder.request;
import static com.github.thxmasj.statemachine.TransitionModel.Builder.onEvent;
import static com.github.thxmasj.statemachine.Tuples.tuple;
import static com.github.thxmasj.statemachine.templates.cardpayment.Identifiers.AcquirerBatchNumber;
import static com.github.thxmasj.statemachine.templates.cardpayment.Identifiers.BatchNumber;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentState.Begin;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentState.Error;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentState.ProcessingSettlement;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentState.Reconciled;
import static com.github.thxmasj.statemachine.templates.cardpayment.Queues.Acquirer;
import static com.github.thxmasj.statemachine.templates.cardpayment.Queues.Merchant;
import static com.github.thxmasj.statemachine.templates.cardpayment.SettlementEvent.CutOffRequest;
import static com.github.thxmasj.statemachine.templates.cardpayment.SettlementEvent.InBalance;
import static com.github.thxmasj.statemachine.templates.cardpayment.SettlementEvent.MerchantCredit;
import static com.github.thxmasj.statemachine.templates.cardpayment.SettlementEvent.MerchantCreditReversed;
import static com.github.thxmasj.statemachine.templates.cardpayment.SettlementEvent.MerchantDebit;
import static com.github.thxmasj.statemachine.templates.cardpayment.SettlementEvent.MerchantDebitReversed;
import static com.github.thxmasj.statemachine.templates.cardpayment.SettlementEvent.Open;
import static com.github.thxmasj.statemachine.templates.cardpayment.SettlementEvent.OutOfBalance;
import static com.github.thxmasj.statemachine.templates.cardpayment.SettlementEvent.Timeout;

import com.github.thxmasj.statemachine.EntityModel;
import com.github.thxmasj.statemachine.IncomingResponseValidator;
import com.github.thxmasj.statemachine.OutboxQueue;
import com.github.thxmasj.statemachine.State;
import com.github.thxmasj.statemachine.TransitionModel;
import com.github.thxmasj.statemachine.Tuples.Tuple2;
import com.github.thxmasj.statemachine.Tuples.Tuple3;
import com.github.thxmasj.statemachine.Tuples.Tuple4;
import com.github.thxmasj.statemachine.database.mssql.SchemaNames;
import com.github.thxmasj.statemachine.message.http.Created;
import com.github.thxmasj.statemachine.templates.cardpayment.AcquirerResponse.ReconciliationValues;
import com.github.thxmasj.statemachine.templates.cardpayment.SettlementEvent.CutOff;
import reactor.core.publisher.Mono;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

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
  public State initialState() {
    return Begin;
  }

  protected abstract IncomingResponseValidator<AcquirerResponse> validateSettlementResponse();

  protected abstract OutgoingRequests.Reconciliation reconciliation();

  protected abstract OutgoingRequests.ApprovedCutOff approvedCutOff();

  @Override
  public List<TransitionModel<?, ?, ?>> transitions() {
    return List.of(
        onEvent(Open).from(Begin).toSelf().build(),
        onEvent(MerchantCredit).from(Begin).toSelf()
            .withData((input, _) -> Mono.just(input.data()))
            .output(Function.identity()),
        onEvent(MerchantDebit).from(Begin).toSelf()
            .withData((input, _) -> Mono.just(input.data()))
            .output(Function.identity()),
        onEvent(MerchantCreditReversed).from(Begin).toSelf()
            .withData((input, _) -> Mono.just(input.data()))
            .output(Function.identity()),
        onEvent(MerchantDebitReversed).from(Begin).toSelf()
            .withData((input, _) -> Mono.just(input.data()))
            .output(Function.identity()),
        onEvent(CutOffRequest).from(Begin).to(ProcessingSettlement)
            .withData(new CutOffRequestDataCreator())
            .output(Tuple3::t1)
            .response(_ -> "", new Created())
            .trigger(_ -> event(Open).onEntity(this)
                .identifiedBy(model(BatchNumber).next().create())
                .and(model(AcquirerBatchNumber).next().create())
            )
            .notify(request((Tuple3<CutOff, BatchNumber, AcquirerBatchNumber> d) -> tuple(d.t2(), d.t3()), reconciliation()).to(Acquirer).guaranteed().responseValidator(validateSettlementResponse())),
        // For previous batch to stay open for ongoing capture exchanges when cut-off is performed
        onEvent(MerchantCredit).from(ProcessingSettlement).toSelf()
            .withData((input, _) -> Mono.just(input.data()))
            .output(Function.identity()),
        onEvent(MerchantDebit).from(ProcessingSettlement).toSelf()
            .withData((input, _) -> Mono.just(input.data()))
            .output(Function.identity()),
        onEvent(MerchantCreditReversed).from(ProcessingSettlement).toSelf()
            .withData((input, _) -> Mono.just(input.data()))
            .output(Function.identity()),
        onEvent(MerchantDebitReversed).from(ProcessingSettlement).toSelf()
            .withData((input, _) -> Mono.just(input.data()))
            .output(Function.identity()),
        onEvent(InBalance).from(ProcessingSettlement).to(Reconciled)
            .withData(new ReconciliationValuesDataCreator())
            .filter(d -> d.t2().equals(d.t3())).orElse(OutOfBalance, Tuple4::t4)
            .notify(request(
                (Tuple4<CutOff, ReconciliationValues, ReconciliationValues, AcquirerResponse> d) -> new Tuple2<>(d.t1(), d.t2()),
                approvedCutOff()
            ).to(Merchant).guaranteed()),
        onEvent(Timeout).from(ProcessingSettlement).to(Error).build(),
        onEvent(OutOfBalance).from(ProcessingSettlement).to(Error).build()
    );
  }

  @Override
  public List<OutboxQueue> queues() {
    return List.of(Queues.values());
  }

}
