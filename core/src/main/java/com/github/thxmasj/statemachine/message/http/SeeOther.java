package com.github.thxmasj.statemachine.message.http;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

public class SeeOther extends HttpResponseCreator {

  public SeeOther() {
    super(303, "See Other");
  }

  @Override
  protected Map<String, String> headers(String correlationId, String data) {
    var map = new TreeMap<>(super.headers(correlationId, data));
    map.put("Location", data);
    return Collections.unmodifiableMap(map);
  }

}
