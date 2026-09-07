package com.github.thxmasj.statemachine.http.inbox;

import com.github.thxmasj.statemachine.Validated;
import com.github.thxmasj.statemachine.http.JsonContentValidator;
import com.github.thxmasj.statemachine.message.http.HttpRequestMessage;
import java.util.function.Function;

public class JsonRequestValidator<T> implements Function<HttpRequestMessage, Validated<T>> {

  private final JsonContentValidator<T> jsonContentParser;

  public JsonRequestValidator(Class<T> contentType, boolean validate) {
    this.jsonContentParser = new JsonContentValidator<>(contentType, validate);
  }

  @Override
  public Validated<T> apply(HttpRequestMessage request) {
    return jsonContentParser.apply(request.body());
  }

}
