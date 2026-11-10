package com.github.thxmasj.backend.core;

import reactor.core.publisher.Mono;

public interface Action<T> {

  String name();

  Mono<InputEvent<?>> execute(T data);

}
