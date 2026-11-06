package com.github.thxmasj.statemachine.http.outbox;

import static com.github.thxmasj.statemachine.Validated.invalid;

import com.github.thxmasj.statemachine.Validated;
import com.github.thxmasj.statemachine.http.JsonContentValidator;
import com.github.thxmasj.statemachine.message.http.HttpResponseMessage;
import java.util.function.Function;

public class JsonResponseValidator<T> implements Function<HttpResponseMessage, Validated<T>> {

  private final int[] acceptedStatusCodes;
  private final JsonContentValidator<T> jsonContentParser;

  public JsonResponseValidator(int acceptedStatusCode, Class<T> contentType, boolean validate) {
    this.acceptedStatusCodes = new int[]{acceptedStatusCode};
    this.jsonContentParser = new JsonContentValidator<>(contentType, validate);
  }

  public JsonResponseValidator(int[] acceptedStatusCodes, Class<T> contentType, boolean validate) {
    this.acceptedStatusCodes = acceptedStatusCodes;
    this.jsonContentParser = new JsonContentValidator<>(contentType, validate);
  }

  @Override
  public Validated<T> apply(HttpResponseMessage response) {
    for (int v : acceptedStatusCodes) {
      if (v == response.statusCode())
        return jsonContentParser.apply(response.body());
    }
    return invalid("Invalid status code: " + response.statusCode());
  }

  public static <T> JsonResponseValidator<T> json(Class<T> contentType) {
    return new JsonResponseValidator<>(new int[]{200, 201, 202, 204}, contentType, true);
  }
}
