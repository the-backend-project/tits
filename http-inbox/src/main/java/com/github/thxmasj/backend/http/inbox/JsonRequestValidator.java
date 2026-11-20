package com.github.thxmasj.backend.http.inbox;

import com.github.thxmasj.backend.core.Validated;
import com.github.thxmasj.backend.core.internal.http.JsonContentValidator;
import com.github.thxmasj.backend.core.message.http.HttpRequest;
import java.util.function.Function;

public class JsonRequestValidator<T> implements Function<HttpRequest<byte[]>, Validated<T>> {

  private final JsonContentValidator<T> jsonContentParser;

  public JsonRequestValidator(Class<T> contentType, boolean validate) {
    this.jsonContentParser = new JsonContentValidator<>(contentType, validate);
  }

  @Override
  public Validated<T> apply(HttpRequest<byte[]> request) {
    return jsonContentParser.apply(request.body());
  }

}
