package com.github.thxmasj.statemachine.message.http;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.github.thxmasj.statemachine.message.http.HttpRequestMessage.Method;
import java.net.URI;
import java.util.Map;
import org.junit.jupiter.api.Test;

public class HttpMessageTest {

  @Test
  public void marshallingIsReversible() {
    byte[] originalBody = "Hello World!".getBytes();
    HttpRequestMessage original = new HttpRequestMessage(
        Method.POST,
        URI.create("/a/b/c"),
        Map.of("Header1", "value1", "Header2", "value2"),
        originalBody
    );
    assertArrayEquals(originalBody, HttpMessageParser.parseRequest(original.toBytes()).body());
  }
}
