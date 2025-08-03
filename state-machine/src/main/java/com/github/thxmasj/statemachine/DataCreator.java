package com.github.thxmasj.statemachine;

import reactor.core.publisher.Mono;

public interface DataCreator<I, O> extends DataRequirer {

  Mono<O> execute(InputEvent<I> inputEvent, Input input);

}
