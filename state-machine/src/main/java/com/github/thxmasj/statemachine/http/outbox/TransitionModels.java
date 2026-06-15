package com.github.thxmasj.statemachine.http.outbox;

import static com.github.thxmasj.statemachine.http.outbox.HttpOutbox.States.Begin;

import com.github.thxmasj.statemachine.State;
import com.github.thxmasj.statemachine.TransitionModelBuilder.TransitionModel;
import java.util.List;
import java.util.Map;

public interface TransitionModels {

  static Map<State, List<TransitionModel<?, ?>>> transitions() {
    return Map.of(
        Begin, List.of()
    );
  }

}
