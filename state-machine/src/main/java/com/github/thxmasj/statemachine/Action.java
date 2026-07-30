package com.github.thxmasj.statemachine;

import reactor.core.publisher.Mono;

public interface Action<T, U> {

  String name();

  Mono<InputEvent<U>> execute(T data);

}
