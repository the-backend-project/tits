package com.github.thxmasj.backend.core.test;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.github.thxmasj.backend.core.EntityModel;
import com.github.thxmasj.backend.core.EventType;
import com.github.thxmasj.backend.core.Listener;
import java.util.*;
import tools.jackson.databind.json.JsonMapper;

public class Logger implements Listener {

  private final String entityName;

  public Logger(String entityName) {
    this.entityName = entityName;
  }

  private void log(String message) {
    System.out.println(message);
  }

  private String header(Object entityId, String correlationId) {
    return String.format("[%s %s %s]", entityName, entityId, correlationId);
  }

  @Override
  public void changeAccepted(String correlationId, List<Change> changes) {
    log("[" + correlationId + "] Changes accepted: \n" + toString(changes));
  }

  private String toString(List<Change> changes) {
    return JsonMapper.builder()
//        .enable(SerializationFeature.INDENT_OUTPUT)
        .changeDefaultPropertyInclusion(incl -> incl.withValueInclusion(JsonInclude.Include.NON_EMPTY).withContentInclusion(JsonInclude.Include.NON_EMPTY))
        .build()
        .writeValueAsString(changes);
  }

  @Override
  public void transitionFailed(String correlationId, EntityModel entityModel, UUID entityId, EventType<?, ?> eventType, Throwable failure) {
    log(header(entityId, correlationId) + " " + eventType.name() + " on " + entityModel.name() + " failed: " + failure);
  }

  @Override
  public void processNextDelayedTriggerFailed(Throwable t) {
    t.printStackTrace();
    log("Process next delayed trigger failed: " + t.toString());
  }
}
