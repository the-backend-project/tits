package com.github.thxmasj.statemachine.templates.cardpayment;

import static com.github.thxmasj.statemachine.Tuples.tuple;

import com.github.thxmasj.statemachine.DataCreator;
import com.github.thxmasj.statemachine.EventLog;
import com.github.thxmasj.statemachine.InputEvent;
import com.github.thxmasj.statemachine.Tuples.Tuple3;
import com.github.thxmasj.statemachine.templates.cardpayment.SettlementEvent.CutOff;

public class CutOffRequestDataCreator implements DataCreator<CutOff, Tuple3<CutOff, BatchNumber, AcquirerBatchNumber>> {

  @Override
  public Tuple3<CutOff, BatchNumber, AcquirerBatchNumber> execute(InputEvent<CutOff> input, EventLog log) {
    return tuple(input.data(), batchNumber(log), acquirerBatchNumber(log));
  }

  public static BatchNumber batchNumber(EventLog log) {
    return log.secondaryIds().stream()
        .filter(id -> id.model() == Identifiers.BatchNumber)
        .map(id -> (BatchNumber)id.data())
        .findFirst()
        .orElseThrow();
  }

  public static AcquirerBatchNumber acquirerBatchNumber(EventLog log) {
    return log.secondaryIds().stream()
        .filter(id -> id.model() == Identifiers.AcquirerBatchNumber)
        .map(id -> (AcquirerBatchNumber)id.data())
        .findFirst()
        .orElseThrow();
  }

}
