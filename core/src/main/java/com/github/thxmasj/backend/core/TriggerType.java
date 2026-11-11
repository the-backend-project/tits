package com.github.thxmasj.backend.core;

/**
 * The kind of stored trigger that an event trigger originates from.
 */
public enum TriggerType {
  /**
   * The event was triggered by a conditional trigger (a row in the ConditionalTrigger table), whose id identifies the row.
   */
  CONDITIONAL
}
