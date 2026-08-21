package com.github.thxmasj.statemachine.templates;

import com.github.thxmasj.statemachine.PlantUMLFormatter;
import com.github.thxmasj.statemachine.templates.cardpayment.DummyPaymentTransitions;
import com.github.thxmasj.statemachine.templates.cardpayment.DummySettlementTransitions;
import java.io.IOException;
import java.util.List;

import static com.github.thxmasj.statemachine.http.inbox.HttpInbox.EntityModels.RequestDispatching;
import static com.github.thxmasj.statemachine.http.inbox.HttpInbox.EntityModels.RequestRouting;
import static com.github.thxmasj.statemachine.http.inbox.TransitionModels.requestDispatchingTransitions;
import static com.github.thxmasj.statemachine.http.inbox.TransitionModels.requestRoutingTransitions;
import static com.github.thxmasj.statemachine.templates.Batching.EntityTypes.Batch;
import static com.github.thxmasj.statemachine.templates.Batching.EntityTypes.Item;
import static com.github.thxmasj.statemachine.templates.cardpayment.Aggregate.Payment;
import static com.github.thxmasj.statemachine.templates.cardpayment.Aggregate.Settlement;

public class Main {

  static void main() throws IOException {
    System.out.println(new PlantUMLFormatter(RequestRouting, requestRoutingTransitions(List.of())).formatToImage("docs/images/"));
    System.out.println(new PlantUMLFormatter(RequestDispatching, requestDispatchingTransitions(List.of(), List.of())).formatToImage("docs/images/"));
    System.out.println(new PlantUMLFormatter(Item, Item.transitions()).formatToImage("docs/images/"));
    System.out.println(new PlantUMLFormatter(Batch, Batch.transitions()).formatToImage("docs/images/"));
    System.out.println(new PlantUMLFormatter(Payment,
        new DummyPaymentTransitions().transitions(
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null
        )
    ).formatToImage("docs/images/"));
    System.out.println(new PlantUMLFormatter(
        Settlement,
        new DummySettlementTransitions().transitions(null, null)
    ).formatToImage("docs/images/"));
  }

}
