package com.github.thxmasj.statemachine;

import java.util.UUID;

public interface OutboxQueue {

  String name();

  UUID id();

}
