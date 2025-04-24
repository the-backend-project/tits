package com.github.thxmasj.statemachine;

import reactor.core.publisher.Mono;

public interface Action extends DataRequirer {

  Mono<Event> execute(EntityId entityId, Input input);

}
