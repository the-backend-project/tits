package com.github.thxmasj.statemachine.templates;

import com.github.thxmasj.statemachine.PlantUMLFormatter;
import com.github.thxmasj.statemachine.templates.cardpayment.DummyPayment;
import com.github.thxmasj.statemachine.templates.cardpayment.DummySettlement;
import java.io.IOException;

import static com.github.thxmasj.statemachine.templates.Batching.EntityTypes.Batch;
import static com.github.thxmasj.statemachine.templates.Batching.EntityTypes.Item;

public class Main {

  public static void main(String[] ignoredArgs) throws IOException {
    System.out.println(new PlantUMLFormatter(Item).formatToImage("docs/images/"));
    System.out.println(new PlantUMLFormatter(Batch).formatToImage("docs/images/"));
    System.out.println(new PlantUMLFormatter(new DummyPayment(null)).formatToImage("docs/images/"));
    System.out.println(new PlantUMLFormatter(new DummySettlement()).formatToImage("docs/images/"));
  }

}
