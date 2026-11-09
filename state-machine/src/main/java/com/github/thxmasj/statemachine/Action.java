package com.github.thxmasj.statemachine;

import reactor.core.publisher.Mono;

public interface Action<T> {

  String name();

  Mono<InputEvent<?>> execute(T data);

}
