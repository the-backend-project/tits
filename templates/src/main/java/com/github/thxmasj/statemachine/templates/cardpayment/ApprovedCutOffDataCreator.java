package com.github.thxmasj.statemachine.templates.cardpayment;

import com.github.thxmasj.statemachine.DataCreator;
import com.github.thxmasj.statemachine.Input;
import com.github.thxmasj.statemachine.InputEvent;
import com.github.thxmasj.statemachine.Requirements;
import com.github.thxmasj.statemachine.templates.cardpayment.AcquirerResponse.ReconciliationValues;
import com.github.thxmasj.statemachine.templates.cardpayment.ApprovedCutOffDataCreator.ApprovedCutOffData;
import com.github.thxmasj.statemachine.templates.cardpayment.SettlementEvent.Type.CutOff;
import reactor.core.publisher.Mono;

import static com.github.thxmasj.statemachine.Requirements.one;
import static com.github.thxmasj.statemachine.templates.cardpayment.SettlementEvent.Type.CutOffRequest;
import static com.github.thxmasj.statemachine.templates.cardpayment.SettlementEvent.Type.SettlementApproved;

public class ApprovedCutOffDataCreator implements DataCreator<String, ApprovedCutOffData> {

  @Override
  public Requirements requirements() {
    return Requirements.of(
        one(CutOffRequest),
        one(SettlementApproved)
    );
  }

  @Override
  public Mono<ApprovedCutOffData> execute(InputEvent<String> inputEvent, Input input) {
    ReconciliationValues reconciliationValues = input.one(SettlementApproved)
        .getUnmarshalledData(AcquirerResponse.class)
        .reconciliationValues();
    CutOff cutOff = input.one(CutOffRequest).getUnmarshalledData(CutOff.class);
    return Mono.just(new ApprovedCutOffData(cutOff, reconciliationValues));
  }

  public record ApprovedCutOffData(
      CutOff cutOff,
      ReconciliationValues reconciliationValues
  ) {}

}
