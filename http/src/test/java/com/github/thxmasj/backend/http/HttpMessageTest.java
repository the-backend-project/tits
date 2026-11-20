package com.github.thxmasj.backend.http;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.github.thxmasj.backend.http.HttpRequest.Method;
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
    assertArrayEquals(originalBody, HttpMessageParser.parseRequest(HttpMessage.toBytes(original)).body());
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
    assertEquals(HttpResponse.create(200, "OK", "done".getBytes()), HttpResponse.create(200, "OK", "done".getBytes()));
  }

  @Test
  public void bodyIsMappedAndTheRestKept() {
    HttpResponse<String> typed = HttpResponse.create(201, "Created", Map.of("Location", "/orders/1"), "order");
    HttpResponse<byte[]> raw = typed.map(String::getBytes);
    assertEquals(new HttpResponse<>(201, "Created", Map.of("Location", "/orders/1"), "order".getBytes()), raw);
    assertEquals("/orders/1", typed.headerValue("location"));
  }

  /// A message with a payload is shown with the payload as text, as it has no bytes to send until it is marshalled
  @Test
  public void messageWithAPayloadIsShownWithItsPayload() {
    var typed = HttpRequest.create(Method.POST, URI.create("/orders"), 42);
    assertEquals("POST /orders\n\n42", typed.toString());
    assertEquals("POST /orders\n\n42", new String(HttpMessage.toBytes(typed.map(n -> n.toString().getBytes()))));
  }

  /// Header names are compared without case, as in HTTP, e.g. a stored request, whose headers are parsed into a map
  /// without case, with a retry of it as received
  @Test
  public void headerNamesAreComparedWithoutCase() {
    var received = new HttpRequest<>(Method.PUT, URI.create("/orders/1"), Map.of("content-type", "text/plain"), "order".getBytes());
    var stored = HttpMessageParser.parseRequest(HttpMessage.toBytes(
        new HttpRequest<>(Method.PUT, URI.create("/orders/1"), Map.of("Content-Type", "text/plain"), "order".getBytes())
    ));
    assertEquals(received, stored);
    assertEquals(stored, received);
    assertEquals(received.hashCode(), stored.hashCode());
    assertNotEquals(received, new HttpRequest<>(Method.PUT, URI.create("/orders/1"), Map.of("content-type", "text/html"), "order".getBytes()));
  }

  /// The URIs of requests are compared as written, as different escapes may be different requests to a server
  @Test
  public void urisAreComparedAsWritten() {
    assertNotEquals(new HttpRequest<>(Method.GET, URI.create("/orders/a%2fb")), new HttpRequest<>(Method.GET, URI.create("/orders/a%2Fb")));
  }

  @Test
  public void messageWithoutHeadersHasAnEmptyMap() {
    assertThrows(NullPointerException.class, () -> HttpResponse.create(204, "No Content", null));
    assertThrows(NullPointerException.class, () -> new HttpRequest<>(Method.GET, URI.create("/"), null));
  }

  /// The data of an event is typed as Object, so a message of bytes is read from it with raw(..)
  @Test
  public void messageOfBytesIsReadFromData() {
    Object response = new HttpResponse<>(200, "OK", Map.of(), "done".getBytes());
    assertArrayEquals("done".getBytes(), HttpResponse.raw(response).body());
    Object request = new HttpRequest<>(Method.GET, URI.create("/orders"));
    assertEquals(URI.create("/orders"), HttpRequest.raw(request).uri());
    assertThrows(IllegalArgumentException.class, () -> HttpResponse.raw(HttpResponse.create(200, "OK", "a payload")));
    assertThrows(IllegalArgumentException.class, () -> HttpRequest.raw("not a request"));
  }
}
