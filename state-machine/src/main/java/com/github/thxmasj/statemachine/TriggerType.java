package com.github.thxmasj.statemachine;

/**
 * The kind of stored trigger that an event trigger originates from.
 */
public enum TriggerType {
  /**
   * The event was triggered by a condition trigger (a row in the ConditionTrigger table), whose id identifies the row.
   */
  CONDITION
}
