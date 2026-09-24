package com.github.thxmasj.statemachine.templates;

import static com.github.thxmasj.statemachine.http.inbox.HttpInbox.EntityModels.RequestDispatching;
import static com.github.thxmasj.statemachine.http.inbox.HttpInbox.EntityModels.RequestRouting;
import static com.github.thxmasj.statemachine.http.inbox.TransitionModels.requestDispatchingTransitions;
import static com.github.thxmasj.statemachine.http.inbox.TransitionModels.requestRoutingTransitions;
import static com.github.thxmasj.statemachine.templates.Batching.EntityTypes.Batch;
import static com.github.thxmasj.statemachine.templates.Batching.EntityTypes.Item;
import static com.github.thxmasj.statemachine.templates.cardpayment.Aggregate.Payment;
import static com.github.thxmasj.statemachine.templates.cardpayment.Aggregate.Settlement;
import static com.github.thxmasj.statemachine.templates.cardpayment.DummyTransitions.atLeastOnce;
import static com.github.thxmasj.statemachine.templates.cardpayment.DummyTransitions.atMostOnce;

import com.github.thxmasj.statemachine.PlantUMLFormatter;
import com.github.thxmasj.statemachine.templates.cardpayment.DummyTransitions;
import java.io.IOException;
import java.util.List;

public class Main {

  static void main() throws IOException {
    var atLeastOnce = atLeastOnce("AtLeastOnce");
    System.out.println(new PlantUMLFormatter(atLeastOnce, atLeastOnce.transitions()).formatToImage("docs/images/"));
    var atMostOnce = atMostOnce("AtMostOnce");
    System.out.println(new PlantUMLFormatter(atMostOnce, atMostOnce.transitions()).formatToFile("docs/images/"));
    System.out.println(new PlantUMLFormatter(RequestRouting, requestRoutingTransitions(List.of())).formatToImage("docs/images/"));
    System.out.println(new PlantUMLFormatter(RequestDispatching, requestDispatchingTransitions(List.of(), List.of())).formatToImage("docs/images/"));
    System.out.println(new PlantUMLFormatter(Item, Item.transitions()).formatToImage("docs/images/"));
    System.out.println(new PlantUMLFormatter(Batch, Batch.transitions()).formatToImage("docs/images/"));
    System.out.println(new PlantUMLFormatter(
        Payment,
        DummyTransitions.payment().transitions()
    ).formatToImage("docs/images/"));
    System.out.println(new PlantUMLFormatter(
        Settlement,
        DummyTransitions.settlement().transitions()
    ).formatToImage("docs/images/"));
  }


}
