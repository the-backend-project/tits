package com.github.thxmasj.statemachine.examples;

import com.github.thxmasj.statemachine.PlantUMLFormatter;
import java.io.IOException;

import static com.github.thxmasj.statemachine.examples.Batching.EntityTypes.Item;

public class Main {

  public static void main(String[] ignoredArgs) throws IOException {
    new PlantUMLFormatter(Item).formatToImage("docs/images/");
  }

}
