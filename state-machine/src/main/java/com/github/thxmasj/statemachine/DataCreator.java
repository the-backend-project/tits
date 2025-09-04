package com.github.thxmasj.statemachine;

public interface DataCreator<I, O> extends DataRequirer {

  O execute(InputEvent<I> inputEvent, EventLog eventLog);

}
