package com.github.thxmasj.statemachine.templates.cardpayment;

import com.github.thxmasj.statemachine.OutboxQueue;
import java.util.UUID;

public enum Queues implements OutboxQueue {
  Acquirer(UUID.fromString("9c58b076-0a3c-4f53-9cd8-91857537ea31")),
  Merchant(UUID.fromString("82ce358d-5270-4a75-aa18-6d23e17a6064")),
  Authenticator(UUID.fromString("fdc42dfe-6f78-4c31-ab81-9661fa3bd3b3"));

  final UUID id;

  Queues(UUID id) {
    this.id = id;
  }

  @Override
  public UUID id() {
    return id;
  }
}
