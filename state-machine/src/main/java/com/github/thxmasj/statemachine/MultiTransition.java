package com.github.thxmasj.statemachine;

import static java.util.Collections.unmodifiableList;

import java.util.ArrayList;
import java.util.List;

public class MultiTransition<O> {

  private final List<Transition<?, ?, ?, ?>> implicitTransitions;
  private final List<Transition<?, ?, ?, ?>> triggeredTransitions;
  private final O output;

  public MultiTransition(
      List<Transition<?, ?, ?, ?>> implicitTransitions,
      List<Transition<?, ?, ?, ?>> triggeredTransitions,
      O output
  ) {
    this.implicitTransitions = implicitTransitions;
    this.triggeredTransitions = triggeredTransitions;
    this.output = output;
  }

  public O output() {
    return output;
  }

  public List<Transition<?, ?, ?, ?>> transitions() {
    List<Transition<?, ?, ?, ?>> l = new ArrayList<>(implicitTransitions.size() + 1);
    l.addAll(implicitTransitions);
    l.addAll(triggeredTransitions);
    return unmodifiableList(l);
  }

  public StateMachine.ProcessResult.Entity entity() {
    return triggeredTransitions.getLast().entity;
  }

  public StateMachine.ProcessResult result() {
    return triggeredTransitions.getLast().result;
  }

}
