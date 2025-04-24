package com.github.thxmasj.statemachine;

import reactor.core.publisher.Mono;

public interface DataCreator<T> extends DataRequirer {

  Mono<T> execute(Input input);

}
