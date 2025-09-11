package com.github.thxmasj.statemachine;

public interface DataCreator<I, O> {

  O execute(InputEvent<I> inputEvent, EventLog eventLog);

}
