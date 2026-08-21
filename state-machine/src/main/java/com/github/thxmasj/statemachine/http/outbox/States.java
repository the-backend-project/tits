package com.github.thxmasj.statemachine.http.outbox;

import com.github.thxmasj.statemachine.*;

import java.time.*;

enum States implements State {
  Begin,
  InFlight,
  InFlightGuaranteed {
    @Override
    public Timeout<?> timeout() {
      return new Timeout<>(Duration.ofSeconds(10), EventTypes.Retry, _ -> null);
    }
  },
  Completed
}
