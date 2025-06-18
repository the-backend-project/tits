package com.github.thxmasj.statemachine.templates.cardpayment;

import com.github.thxmasj.statemachine.Subscriber;
import java.util.UUID;

public enum Subscribers implements Subscriber {
  Acquirer(UUID.fromString("9c58b076-0a3c-4f53-9cd8-91857537ea31")),
  Merchant(UUID.fromString("82ce358d-5270-4a75-aa18-6d23e17a6064"));

  final UUID id;

  Subscribers(UUID id) {
    this.id = id;
  }

  @Override
  public UUID id() {
    return id;
  }
}
