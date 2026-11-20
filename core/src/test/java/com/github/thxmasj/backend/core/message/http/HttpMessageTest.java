package com.github.thxmasj.backend.core.message.http;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.github.thxmasj.backend.core.message.http.HttpRequest.Method;
import java.net.URI;
import java.util.Map;
import org.junit.jupiter.api.Test;

public class HttpMessageTest {

  @Test
  public void marshallingIsReversible() {
    byte[] originalBody = "Hello World!".getBytes();
    HttpRequest<byte[]> original = new HttpRequest<>(
        Method.POST,
        URI.create("/a/b/c"),
        Map.of("Header1", "value1", "Header2", "value2"),
        originalBody
    );
    assertArrayEquals(originalBody, HttpMessageParser.parseRequest(original.toBytes()).body());
  }

  /// Raw messages are equal by the content of their bodies, as the bytes of two messages read are different arrays
  @Test
  public void messagesWithEqualBodiesAreEqual() {
    var request = new HttpRequest<>(Method.PUT, URI.create("/orders/1"), Map.of("A", "1"), "order".getBytes());
    var same = new HttpRequest<>(Method.PUT, URI.create("/orders/1"), Map.of("A", "1"), "order".getBytes());
    var other = new HttpRequest<>(Method.PUT, URI.create("/orders/1"), Map.of("A", "1"), "other".getBytes());
    assertEquals(request, same);
    assertEquals(request.hashCode(), same.hashCode());
    assertNotEquals(request, other);
    assertEquals(new HttpResponse<>(200, "OK", "done".getBytes()), new HttpResponse<>(200, "OK", "done".getBytes()));
  }

  @Test
  public void bodyIsMappedAndTheRestKept() {
    HttpResponse<String> typed = HttpResponse.create(201, "Created", Map.of("Location", "/orders/1"), "order");
    HttpResponse<byte[]> raw = typed.map(String::getBytes);
    assertEquals(new HttpResponse<>(201, "Created", Map.of("Location", "/orders/1"), "order".getBytes()), raw);
    assertEquals("/orders/1", typed.headerValue("location"));
  }

  /// A payload which isn't marshalled has no bytes to send
  @Test
  public void messageWithAPayloadHasNoBytes() {
    var typed = HttpRequest.create(Method.POST, URI.create("/orders"), 42);
    assertThrows(IllegalStateException.class, typed::toBytes);
    assertEquals("POST /orders\n\n42", typed.toString());
  }
}
