package com.github.thxmasj.statemachine;

import com.github.thxmasj.statemachine.OutboxWorker.ResponseEvaluator;
import com.github.thxmasj.statemachine.http.HttpClient;
import java.util.function.UnaryOperator;

public interface Subscriber {

  String name();

  HttpClient client();

  default ResponseEvaluator responseEvaluator() {
    return new ResponseEvaluator() {};
  }

  default UnaryOperator<String> reattemptTransformation() {
    return null;
  }

}
