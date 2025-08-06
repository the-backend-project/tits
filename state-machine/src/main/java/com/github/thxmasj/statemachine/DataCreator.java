package com.github.thxmasj.statemachine;

import reactor.core.publisher.Mono;

import static com.github.thxmasj.statemachine.Requirements.one;

public interface DataCreator<I, O> extends DataRequirer {

  Mono<O> execute(InputEvent<I> inputEvent, Input input);

  static <T> DataCreator<T, T> fromInput(Class<T> unused) {
    return (inputEvent, _) -> Mono.just(inputEvent.data());
  }

  static <T> DataCreator<Void, T> fromEvent(EventType eventType, Class<T> dataType) {
    return new DataCreator<>() {
      @Override
      public Mono<T> execute(InputEvent<Void> inputEvent, Input input) {
        return Mono.just(input.one(eventType).getUnmarshalledData(dataType));
      }

      @Override
      public Requirements requirements() {
        return Requirements.of(one(eventType));
      }

    };
  }

}
