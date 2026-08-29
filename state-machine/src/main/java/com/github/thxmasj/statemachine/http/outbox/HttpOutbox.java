package com.github.thxmasj.statemachine.http.outbox;

import com.github.thxmasj.statemachine.*;
import com.github.thxmasj.statemachine.message.http.*;

import java.util.*;

public sealed interface HttpOutbox<I> extends com.github.thxmasj.statemachine.EntityModel permits AtLeastOnce, AtMostOnce {

  Map<State, List<TransitionModelBuilder.TransitionModel<?, ?>>> transitions();

  default State initialState() {
    return AtMostOnce.States.Begin;
  }

  EventType<I, HttpRequestMessage> requestDispatched();
}
