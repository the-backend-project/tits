package com.github.thxmasj.statemachine.http.outbox;

import static com.github.thxmasj.statemachine.Validated.invalid;
import static com.github.thxmasj.statemachine.Validated.valid;

import com.github.thxmasj.statemachine.Validated;
import com.github.thxmasj.statemachine.message.http.HttpResponseMessage;
import java.util.function.Function;

public class AnyResponseValidator implements Function<HttpResponseMessage, Validated<String>> {

  private final int[] acceptedStatusCodes;

  public AnyResponseValidator(int acceptedStatusCode) {
    this.acceptedStatusCodes = new int[]{acceptedStatusCode};
  }

  public AnyResponseValidator(int[] acceptedStatusCodes) {
    this.acceptedStatusCodes = acceptedStatusCodes;
  }

  @Override
  public Validated<String> apply(HttpResponseMessage response) {
    for (int v : acceptedStatusCodes) {
      if (v == response.statusCode())
        return valid(response.body());
    }
    return invalid("Invalid status code: " + response.statusCode());
  }

  public static <T> AnyResponseValidator any() {
    return new AnyResponseValidator(new int[]{200, 201, 202, 204});
  }
}
