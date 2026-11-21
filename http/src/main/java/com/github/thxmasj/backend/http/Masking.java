package com.github.thxmasj.backend.http;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.net.URI;
import java.net.URLDecoder;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/// What of the requests and responses of an inbox request, or of an at-most-once outbox request, is masked in the event
/// log: the values of headers, of
/// query parameters and of the fields of a form body, or the whole body of a response. The transitions see the whole
/// request and response, while the event log has [#MASK] in place of what is masked, e.g. of a session cookie, an
/// authorization code or a token.
///
/// ```java
/// .masking(Masking.queryParameters("code").andFormFields("client_secret").andResponseBody())
/// ```
///
/// Every inbox request and at-most-once outbox request masks [#CREDENTIALS], the headers which carry credentials, and
/// what its `masking` adds.
///
/// @param headers         the names of the headers whose values are masked, in requests and responses, compared
///                        without case
/// @param queryParameters the names of the query parameters of a request whose values are masked
/// @param formFields      the names of the fields of an `application/x-www-form-urlencoded` body whose values are
///                        masked, in requests and responses
/// @param wholeResponseBody whether the body of a response is masked as a whole, e.g. one with a token
public record Masking(Set<String> headers, Set<String> queryParameters, Set<String> formFields, boolean wholeResponseBody) {

  /// The value of what is masked in the event log
  public static final String MASK = "***";

  /// Masks nothing
  public static final Masking NONE = new Masking(Set.of(), Set.of(), Set.of(), false);

  /// The headers which carry credentials, masked in the event log of every inbox request
  public static final Masking CREDENTIALS = headers("Authorization", "Proxy-Authorization", "Cookie", "Set-Cookie");

  public Masking {
    headers = headers.stream().map(h -> h.toLowerCase(Locale.ROOT)).collect(Collectors.toUnmodifiableSet());
    queryParameters = Set.copyOf(queryParameters);
    formFields = Set.copyOf(formFields);
  }

  /// Masks the values of the headers
  public static Masking headers(String... names) {
    return NONE.andHeaders(names);
  }

  /// Masks the values of the query parameters
  public static Masking queryParameters(String... names) {
    return NONE.andQueryParameters(names);
  }

  /// Masks the values of the fields of a form body
  public static Masking formFields(String... names) {
    return NONE.andFormFields(names);
  }

  /// Masks the body of a response
  public static Masking responseBody() {
    return NONE.andResponseBody();
  }

  /// This masking and the values of the headers
  public Masking andHeaders(String... names) {
    return new Masking(union(headers, names), queryParameters, formFields, wholeResponseBody);
  }

  /// This masking and the values of the query parameters
  public Masking andQueryParameters(String... names) {
    return new Masking(headers, union(queryParameters, names), formFields, wholeResponseBody);
  }

  /// This masking and the values of the fields of a form body
  public Masking andFormFields(String... names) {
    return new Masking(headers, queryParameters, union(formFields, names), wholeResponseBody);
  }

  /// This masking and the body of a response
  public Masking andResponseBody() {
    return new Masking(headers, queryParameters, formFields, true);
  }

  /// What this masking or the other masks
  public Masking and(Masking other) {
    return new Masking(
        union(headers, other.headers.toArray(String[]::new)),
        union(queryParameters, other.queryParameters.toArray(String[]::new)),
        union(formFields, other.formFields.toArray(String[]::new)),
        wholeResponseBody || other.wholeResponseBody
    );
  }

  private static Set<String> union(Set<String> set, String... names) {
    var union = new TreeSet<>(set);
    union.addAll(List.of(names));
    return union;
  }

  /// The request as it is stored, with what is masked replaced by [#MASK]
  public HttpRequest<byte[]> mask(HttpRequest<byte[]> request) {
    if (request == null)
      return null;
    return new HttpRequest<>(request.method(), maskQuery(request.uri()), maskHeaders(request.headers()), maskForm(request));
  }

  /// The response as it is stored, with what is masked replaced by [#MASK]
  public HttpResponse<byte[]> mask(HttpResponse<byte[]> response) {
    if (response == null)
      return null;
    byte[] body = wholeResponseBody && response.body() != null ? MASK.getBytes(UTF_8) : maskForm(response);
    return new HttpResponse<>(response.statusCode(), response.reasonPhrase(), maskHeaders(response.headers()), body);
  }

  /// Whether the response, as stored, has something masked: a header or form field this masking masks, or its body if
  /// this masking masks the body of a response
  public boolean hasMasked(HttpResponse<byte[]> stored) {
    if (stored == null)
      return false;
    if (wholeResponseBody && stored.body() != null)
      return true;
    if (stored.headers().keySet().stream().anyMatch(this::isMaskedHeader))
      return true;
    if (formFields.isEmpty() || stored.body() == null || !isForm(stored))
      return false;
    return Stream.of(new String(stored.body(), UTF_8).split("&", -1))
        // By the value, as the name of a field is kept as written, which may be encoded
        .anyMatch(pair -> pair.equals(MASK) || pair.endsWith("=" + MASK));
  }

  private Map<String, String> maskHeaders(Map<String, String> original) {
    if (headers.isEmpty() || original.keySet().stream().noneMatch(this::isMaskedHeader))
      return original;
    var masked = new LinkedHashMap<String, String>();
    original.forEach((name, value) -> masked.put(name, isMaskedHeader(name) ? MASK : value));
    return masked;
  }

  private boolean isMaskedHeader(String name) {
    return headers.contains(name.toLowerCase(Locale.ROOT));
  }

  /// The URI with the query masked. The query starts at the first `?`, which a path can't have, and ends at a
  /// fragment, if any.
  private URI maskQuery(URI uri) {
    if (queryParameters.isEmpty() || uri.getRawQuery() == null)
      return uri;
    String s = uri.toString();
    int query = s.indexOf('?');
    int fragment = s.indexOf('#', query);
    return URI.create(
        s.substring(0, query + 1) + maskParameters(uri.getRawQuery(), queryParameters) + (fragment >= 0 ? s.substring(fragment) : "")
    );
  }

  private byte[] maskForm(HttpMessage<byte[]> message) {
    byte[] body = message.body();
    if (formFields.isEmpty() || body == null || !isForm(message))
      return body;
    return maskParameters(new String(body, UTF_8), formFields).getBytes(UTF_8);
  }

  private static boolean isForm(HttpMessage<byte[]> message) {
    String contentType = message.headerValue("Content-Type");
    return contentType != null && contentType.toLowerCase(Locale.ROOT).startsWith("application/x-www-form-urlencoded");
  }

  /// The parameters, as written, with the values of those with the names masked. A parameter whose name can't be
  /// decoded is masked as a whole, as it may be one of them.
  private static String maskParameters(String parameters, Set<String> names) {
    return Stream.of(parameters.split("&", -1))
        .map(pair -> {
          int i = pair.indexOf('=');
          String rawName = i < 0 ? pair : pair.substring(0, i);
          String name;
          try {
            name = URLDecoder.decode(rawName, UTF_8);
          } catch (IllegalArgumentException e) {
            return MASK;
          }
          return names.contains(name) ? rawName + "=" + MASK : pair;
        })
        .collect(Collectors.joining("&"));
  }

}
