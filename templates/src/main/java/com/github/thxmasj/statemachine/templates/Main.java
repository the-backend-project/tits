package com.github.thxmasj.statemachine.templates;

import com.github.thxmasj.statemachine.PlantUMLFormatter;
import com.github.thxmasj.statemachine.templates.cardpayment.DummyPaymentTransitions;
import com.github.thxmasj.statemachine.templates.cardpayment.DummySettlementTransitions;
import java.io.IOException;

import static com.github.thxmasj.statemachine.templates.Batching.EntityTypes.Batch;
import static com.github.thxmasj.statemachine.templates.Batching.EntityTypes.Item;
import static com.github.thxmasj.statemachine.templates.cardpayment.Aggregate.Payment;
import static com.github.thxmasj.statemachine.templates.cardpayment.Aggregate.Settlement;

public class Main {

  static void main() throws IOException {
    System.out.println(new PlantUMLFormatter(Item, Item.transitions()).formatToImage("docs/images/"));
    System.out.println(new PlantUMLFormatter(Batch, Batch.transitions()).formatToImage("docs/images/"));
    System.out.println(new PlantUMLFormatter(Payment, new DummyPaymentTransitions().transitions()).formatToImage("docs/images/"));
    System.out.println(new PlantUMLFormatter(Settlement, new DummySettlementTransitions().transitions()).formatToImage("docs/images/"));
  }

}
