package com.github.thxmasj.backend.templates.cardpayment;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.github.thxmasj.backend.core.Listener;
import java.util.List;
import tools.jackson.databind.json.JsonMapper;

public class Logger implements Listener {

  private final String entityName;

  public Logger(String entityName) {
    this.entityName = entityName;
  }

  private void log(String message) {
    System.out.println(message);
  }

  @Override
  public void changeAccepted(String correlationId, List<Change> changes) {
    log("[" + correlationId + "] Changes accepted: \n" + toString(changes));
  }

  private String toString(List<Change> changes) {
    return JsonMapper.builder()
        //.enable(SerializationFeature.INDENT_OUTPUT)
        .changeDefaultPropertyInclusion(incl -> incl.withValueInclusion(JsonInclude.Include.NON_EMPTY).withContentInclusion(JsonInclude.Include.NON_EMPTY))
        .build()
        .writeValueAsString(changes);
  }

  @Override
  public void processNextDelayedTriggerFailed(Throwable t) {
    log("Process next delayed trigger failed: " + t.toString());
  }
}
