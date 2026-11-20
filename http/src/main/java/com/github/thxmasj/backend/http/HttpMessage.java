package com.github.thxmasj.backend.http;

import static java.util.stream.Collectors.joining;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.TreeMap;

/// An HTTP request or response, with a body of type `T`: the bytes of the body as sent, `byte[]`, or the payload they
/// are parsed into, or marshalled from. A message without a body has a null body.
public sealed interface HttpMessage<T> permits HttpRequest, HttpResponse {

  Map<String, String> headers();

  T body();

  /// The start line, i.e. the request line of a request and the status line of a response
  String startLine();

  /// The value of the header, whose name is compared without case, or null if the message has none
  default String headerValue(String header) {
    return headers().entrySet().stream()
        .filter(kv -> kv.getKey().equalsIgnoreCase(header))
        .map(Entry::getValue)
        .findFirst()
        .orElse(null);
  }

  /// The message as sent: its start line, headers and body, which [HttpMessageParser] parses. Only a message whose body
  /// is bytes, or which has none, is sent, so a payload is marshalled into bytes first, e.g. with
  /// [HttpRequest#map(java.util.function.Function)].
  static byte[] toBytes(HttpMessage<byte[]> message) {
    byte[] head = head(message).getBytes(StandardCharsets.UTF_8);
    byte[] body = message.body();
    if (body == null)
      return head;
    return ByteBuffer.allocate(head.length + 2 + body.length)
        .put(head)
        .put("\n\n".getBytes(StandardCharsets.UTF_8))
        .put(body)
        .array();
  }

  /// The message as text: as sent, if the body is bytes or absent, and otherwise with the payload as its string
  static String toString(HttpMessage<?> message) {
    Object body = message.body();
    if (body == null)
      return head(message);
    return head(message) + "\n\n" + (body instanceof byte[] bytes ? new String(bytes, StandardCharsets.UTF_8) : body);
  }

  /// Whether the headers are equal, with their names compared without case, as they are in HTTP
  static boolean headersEqual(Map<String, String> headers, Map<String, String> other) {
    return caseInsensitive(headers).equals(caseInsensitive(other));
  }

  /// The hash code of the headers, consistent with [#headersEqual(Map, Map)]
  static int headersHashCode(Map<String, String> headers) {
    int hash = 0;
    for (var header : headers.entrySet())
      hash += header.getKey().toLowerCase(Locale.ROOT).hashCode() ^ Objects.hashCode(header.getValue());
    return hash;
  }

  /// Whether the bodies are equal, by content if they are bytes
  static boolean bodiesEqual(Object body, Object other) {
    return body instanceof byte[] bytes && other instanceof byte[] otherBytes
        ? Arrays.equals(bytes, otherBytes)
        : Objects.equals(body, other);
  }

  /// The hash code of the body, consistent with [#bodiesEqual(Object, Object)]
  static int bodyHashCode(Object body) {
    return body instanceof byte[] bytes ? Arrays.hashCode(bytes) : Objects.hashCode(body);
  }

  /// The start line and the headers
  private static String head(HttpMessage<?> message) {
    if (message.headers().isEmpty())
      return message.startLine();
    return message.startLine() + "\n" + message.headers().entrySet().stream()
        .map(entry -> entry.getKey() + ":" + entry.getValue())
        .collect(joining("\n"));
  }

  private static Map<String, String> caseInsensitive(Map<String, String> headers) {
    var map = new TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER);
    map.putAll(headers);
    return map;
  }
}
