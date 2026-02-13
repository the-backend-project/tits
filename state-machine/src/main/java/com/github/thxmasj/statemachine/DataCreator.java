package com.github.thxmasj.statemachine;

public interface DataCreator<I, P> {

  P execute(InputEvent<I> inputEvent, EventLog eventLog);

}
