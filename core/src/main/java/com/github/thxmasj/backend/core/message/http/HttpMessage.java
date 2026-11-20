package com.github.thxmasj.backend.core.message.http;

import static java.util.stream.Collectors.joining;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Map.Entry;

/// An HTTP request or response, with a body of type `T`: the bytes of the body as sent, e.g. `byte[]`, or the payload
/// they are parsed into, or marshalled from. A message without a body has a null body.
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

  /// The message as sent: its start line, headers and body, which [HttpMessageParser] parses
  ///
  /// @throws IllegalStateException if the body is neither bytes nor absent, e.g. a payload not marshalled
  default byte[] toBytes() {
    if (body() != null && !(body() instanceof byte[]))
      throw new IllegalStateException("The body of the message is not bytes, but " + body().getClass().getName());
    String head = startLine();
    if (!headers().isEmpty()) {
      head = head + "\n" + headers().entrySet().stream()
          .map(entry -> entry.getKey() + ":" + entry.getValue())
          .collect(joining("\n"));
    }
    byte[] headBytes = head.getBytes(StandardCharsets.UTF_8);
    if (!(body() instanceof byte[] body))
      return headBytes;
    return ByteBuffer.allocate(headBytes.length + 2 + body.length)
        .put(headBytes)
        .put("\n\n".getBytes(StandardCharsets.UTF_8))
        .put(body)
        .array();
  }

  /// The message as text: as sent, if the body is bytes or absent, and otherwise with the payload as its string
  static String toString(HttpMessage<?> message) {
    if (message.body() == null || message.body() instanceof byte[])
      return new String(message.toBytes(), StandardCharsets.UTF_8);
    String head = message.startLine();
    if (!message.headers().isEmpty())
      head = head + "\n" + message.headers().entrySet().stream()
          .map(entry -> entry.getKey() + ":" + entry.getValue())
          .collect(joining("\n"));
    return head + "\n\n" + message.body();
  }
}
