package com.github.thxmasj.statemachine.examples;

import com.github.thxmasj.statemachine.PlantUMLFormatter;
import java.io.IOException;

public class Main {

  public static void main(String[] args) throws IOException {
    new PlantUMLFormatter(RequestReply.INSTANCE).formatToImage("docs");
  }

}
