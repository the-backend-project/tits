package com.github.thxmasj.statemachine.http.outbox;

import com.github.thxmasj.statemachine.EntityModel;
import com.github.thxmasj.statemachine.State;
import java.util.UUID;

import static com.github.thxmasj.statemachine.http.outbox.HttpOutbox.States.Begin;

public interface HttpOutbox {

  enum States implements State {Begin}

  enum EntityModels implements EntityModel {
    Exchange {
      @Override
      public UUID id() {
        return UUID.fromString("32f8bc1c-ffce-44a2-82ae-619e04c69bf5");
      }

      @Override
      public State initialState() {
        return Begin;
      }
    }

  }

}
