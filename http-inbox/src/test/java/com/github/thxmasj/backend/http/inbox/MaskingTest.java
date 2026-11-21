package com.github.thxmasj.backend.http.inbox;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.github.thxmasj.backend.http.HttpRequest;
import com.github.thxmasj.backend.http.HttpRequest.Method;
import com.github.thxmasj.backend.http.HttpResponse;
import java.net.URI;
import java.util.Map;
import org.junit.jupiter.api.Test;

public class MaskingTest {

  private static final Map<String, String> form = Map.of("Content-Type", "application/x-www-form-urlencoded");

  @Test
  public void credentialHeadersAreMaskedWhateverTheirCase() {
    var request = new HttpRequest<byte[]>(Method.GET, URI.create("/"), Map.of("authorization", "Bearer x", "COOKIE", "s=1", "Accept", "text/html"), null);
    var masked = Masking.CREDENTIALS.mask(request);
    assertEquals(Map.of("authorization", "***", "COOKIE", "***", "Accept", "text/html"), masked.headers());
    var response = new HttpResponse<byte[]>(302, "Found", Map.of("Set-Cookie", "s=1; HttpOnly", "Location", "/"), null);
    assertEquals(Map.of("Set-Cookie", "***", "Location", "/"), Masking.CREDENTIALS.mask(response).headers());
  }

  @Test
  public void queryParametersAreMaskedAsWrittenKeepingTheOthersAndTheFragment() {
    var request = new HttpRequest<byte[]>(Method.GET, URI.create("https://rp.example.com/cb?state=a%20b&code=s3cr%2Bt#top"), Map.of(), null);
    var masked = Masking.queryParameters("code").mask(request);
    assertEquals("https://rp.example.com/cb?state=a%20b&code=***#top", masked.uri().toString());
  }

  @Test
  public void aRequestWithNothingToMaskIsTheSame() {
    var request = new HttpRequest<byte[]>(Method.GET, URI.create("/cb?state=x"), Map.of("Accept", "*/*"), null);
    assertEquals(request, Masking.queryParameters("code").mask(request));
  }

  @Test
  public void formFieldsAreMaskedOnlyInFormBodies() {
    var masking = Masking.formFields("client_secret", "code");
    var request = new HttpRequest<>(Method.POST, URI.create("/token"), form, "grant_type=authorization_code&code=abc&client_secret=xyz".getBytes(UTF_8));
    assertEquals("grant_type=authorization_code&code=***&client_secret=***", new String(masking.mask(request).body(), UTF_8));
    var json = new HttpRequest<>(Method.POST, URI.create("/token"), Map.of("Content-Type", "application/json"), "{\"code\":\"abc\"}".getBytes(UTF_8));
    assertEquals("{\"code\":\"abc\"}", new String(masking.mask(json).body(), UTF_8));
  }

  @Test
  public void aParameterWhoseNameCantBeDecodedIsMaskedAsAWhole() {
    var request = new HttpRequest<>(Method.POST, URI.create("/token"), form, "a=1&%zz=2".getBytes(UTF_8));
    assertEquals("a=1&***", new String(Masking.formFields("code").mask(request).body(), UTF_8));
  }

  @Test
  public void theBodyOfAResponseIsMaskedAsAWhole() {
    var response = new HttpResponse<>(200, "OK", Map.of("Content-Type", "application/json"), "{\"access_token\":\"x\"}".getBytes(UTF_8));
    assertEquals("***", new String(Masking.responseBody().mask(response).body(), UTF_8));
    assertEquals(null, Masking.responseBody().mask(new HttpResponse<byte[]>(204, "No Content", Map.of(), null)).body());
  }

  @Test
  public void maskingsAreCombined() {
    var masking = Masking.CREDENTIALS.and(Masking.queryParameters("code").andHeaders("X-Api-Key"));
    var request = new HttpRequest<byte[]>(Method.GET, URI.create("/cb?code=1"), Map.of("X-Api-Key", "k", "Cookie", "c"), null);
    var masked = masking.mask(request);
    assertEquals(Map.of("X-Api-Key", "***", "Cookie", "***"), masked.headers());
    assertEquals("/cb?code=***", masked.uri().toString());
  }

  @Test
  public void aStoredResponseHasSomethingMaskedIfItHasAMaskedHeaderOrBody() {
    var cookie = Masking.CREDENTIALS.mask(new HttpResponse<byte[]>(200, "OK", Map.of("Set-Cookie", "s=1"), null));
    assertEquals(true, Masking.CREDENTIALS.hasMasked(cookie));
    assertEquals(false, Masking.CREDENTIALS.hasMasked(new HttpResponse<>(201, "Created", Map.of("Content-Type", "application/json"), "{}".getBytes(UTF_8))));
    assertEquals(true, Masking.responseBody().hasMasked(new HttpResponse<>(200, "OK", Map.of(), "***".getBytes(UTF_8))));
    assertEquals(false, Masking.responseBody().hasMasked(new HttpResponse<byte[]>(204, "No Content", Map.of(), null)));
  }
}
