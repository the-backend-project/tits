package com.github.thxmasj.backend.http.outbox;

import static com.github.thxmasj.backend.core.Validated.invalid;
import static com.github.thxmasj.backend.core.Validated.valid;

import com.github.thxmasj.backend.core.Validated;
import com.github.thxmasj.backend.core.message.http.HttpResponseMessage;
import java.util.function.Function;

public class AnyResponseValidator implements Function<HttpResponseMessage, Validated<byte[]>> {

  private final int[] acceptedStatusCodes;

  public AnyResponseValidator(int acceptedStatusCode) {
    this.acceptedStatusCodes = new int[]{acceptedStatusCode};
  }

  public AnyResponseValidator(int[] acceptedStatusCodes) {
    this.acceptedStatusCodes = acceptedStatusCodes;
  }

  @Override
  public Validated<byte[]> apply(HttpResponseMessage response) {
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
