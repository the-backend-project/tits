package com.github.thxmasj.statemachine.templates;

import com.github.thxmasj.statemachine.PlantUMLFormatter;
import java.io.IOException;

import static com.github.thxmasj.statemachine.templates.Batching.EntityTypes.Batch;
import static com.github.thxmasj.statemachine.templates.Batching.EntityTypes.Item;

public class Main {

  public static void main(String[] ignoredArgs) throws IOException {
    new PlantUMLFormatter(Item).formatToImage("docs/images/");
    new PlantUMLFormatter(Batch).formatToImage("docs/images/");
  }

}
