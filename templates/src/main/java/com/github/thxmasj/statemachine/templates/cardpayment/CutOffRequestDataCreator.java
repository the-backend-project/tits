package com.github.thxmasj.statemachine.templates.cardpayment;

import static com.github.thxmasj.statemachine.Tuples.tuple;

import com.github.thxmasj.statemachine.DataCreator;
import com.github.thxmasj.statemachine.EventLog;
import com.github.thxmasj.statemachine.InputEvent;
import com.github.thxmasj.statemachine.Tuples.Tuple3;
import com.github.thxmasj.statemachine.templates.cardpayment.SettlementEvent.CutOff;
import reactor.core.publisher.Mono;

public class CutOffRequestDataCreator implements DataCreator<CutOff, Tuple3<CutOff, BatchNumber, AcquirerBatchNumber>> {

  @Override
  public Mono<Tuple3<CutOff, BatchNumber, AcquirerBatchNumber>> execute(InputEvent<CutOff> inputEvent, EventLog eventLog) {
    BatchNumber currentId = eventLog.secondaryIds().stream()
        .filter(id -> id.model() == Identifiers.BatchNumber)
        .map(id -> (BatchNumber)id.data())
        .findFirst()
        .orElseThrow();
    AcquirerBatchNumber currentNetsSession = eventLog.secondaryIds().stream()
        .filter(id -> id.model() == Identifiers.AcquirerBatchNumber)
        .map(id -> (AcquirerBatchNumber)id.data())
        .findFirst()
        .orElseThrow();
    return Mono.just(tuple(inputEvent.data(), currentId, currentNetsSession));
  }
}
