package com.github.thxmasj.statemachine;

import com.github.thxmasj.statemachine.http.HttpClient;
import java.util.function.UnaryOperator;

public interface Subscriber {

  String name();

  HttpClient client();

  default UnaryOperator<String> reattemptTransformation() {
    return null;
  }

}
