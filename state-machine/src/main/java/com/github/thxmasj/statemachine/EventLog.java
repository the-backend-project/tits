package com.github.thxmasj.statemachine;

import java.util.ArrayList;
import java.util.List;

import static java.util.Collections.unmodifiableList;

public record EventLog(EntityModel entityModel, EntityId entityId, List<SecondaryId> secondaryIds, List<Event> events) {

  public int lastEventNumber() {
    return events.isEmpty() ? 0 : events.getLast().eventNumber();
  }

  /**
   * Effective events, ignoring events that were rolled back.
   */
  public List<Event> effectiveEvents() {
    if (!events.isEmpty() && events.getLast().type().isCancel()) {
      return List.of();
    }
    // Traverse the event log backwards and skip events between a rollback and client request
    // non-greedily and inclusively
    var effectiveEvents = new ArrayList<>(events);
    boolean skip = false;
    for (int i = effectiveEvents.size() - 1; i >= 0; i--) {
      if (effectiveEvents.get(i).type().isRollback()) {
        if (effectiveEvents.get(i).data() == null || Integer.parseInt(effectiveEvents.get(i).data()) > 0) {
          // Rollback arrived after incoming request => skip events backwards until incoming request
          skip = true;
        }
        // Always remove rollback event itself
        effectiveEvents.remove(i);
      } else if (effectiveEvents.get(i).isIncomingRequest() && skip) {
        // Last event to skip
        effectiveEvents.remove(i);
        skip = false;
      } else if (skip) {
        effectiveEvents.remove(i);
      }
    }
    return unmodifiableList(effectiveEvents);
  }
}
