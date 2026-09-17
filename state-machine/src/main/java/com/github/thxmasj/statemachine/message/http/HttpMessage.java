package com.github.thxmasj.statemachine.message.http;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;

import static java.util.stream.Collectors.joining;

public sealed abstract class HttpMessage permits HttpRequestMessage, HttpResponseMessage {

  private final Map<String, String> headers;
  private final byte[] body;

  protected HttpMessage(Map<String, String> headers, byte[] body) {
    this.headers = headers;
    this.body = body;
  }

  public Map<String, String> headers() {
    return headers;
  }

  public byte[] body() {
    return body;
  }

  public String headerValue(String header) {
    return headers.entrySet().stream()
        .filter(kv -> kv.getKey().equalsIgnoreCase(header))
        .map(Entry::getValue)
        .findFirst()
        .orElse(null);
  }

  @Override
  public String toString() {
    return toString(head());
  }

  public byte[] toBytes() {
    return toBytes(head());
  }

  public abstract String head();

  protected String toString(String head) {
    return new String(toBytes(head));
  }

  protected byte[] toBytes(String head) {
    String m = head;
    if (!headers.isEmpty()) {
      m = m + "\n" + headers.entrySet().stream()
          .map(entry -> entry.getKey() + ":" + entry.getValue())
          .collect(joining("\n"));
    }
    if (body != null) {
      return concat(m.getBytes(), "\n\n".getBytes(), body);
    } else {
      return m.getBytes();
    }
  }

  protected boolean equals(HttpMessage other) {
    return Objects.equals(head(), other.head()) && Arrays.equals(body, other.body);
  }

  private static byte[] concat(byte[]... arrays) {
    int totalLength = 0;
    for (byte[] array : arrays) {
      totalLength += array.length;
    }
    ByteBuffer buffer = ByteBuffer.allocate(totalLength);
    for (byte[] array : arrays) {
      buffer.put(array);
    }
    return buffer.array();
  }

  @Override
  public int hashCode() {
    return Objects.hash(head(), Arrays.hashCode(body()));
  }

}
