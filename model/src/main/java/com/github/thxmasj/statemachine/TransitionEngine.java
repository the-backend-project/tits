package com.github.thxmasj.statemachine;

import com.github.thxmasj.statemachine.TransitionModelBuilder.ChangeContext;
import reactor.core.publisher.Mono;

/**
 * The operations a transition model needs from the state machine executing it.
 */
public interface TransitionEngine {

  Traverser traverser(EntityModel entityModel);

  <T> Mono<SecondaryId<T>> next(SecondaryIdModel<T> idModel, Object idGroup);

  <T, I, O> Mono<ChangeContext<ProcessResult<O>>> calculateTriggeredEvent(
      EventTrigger<T, I, O> eventTrigger,
      ChangeContext<?> tail,
      T data,
      boolean skipCircularCheck,
      EventReference triggerEvent
  );

}
