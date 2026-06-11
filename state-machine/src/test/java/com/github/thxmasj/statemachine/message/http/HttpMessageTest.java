package com.github.thxmasj.statemachine.message.http;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.github.thxmasj.statemachine.message.http.HttpRequestMessage.Method;
import java.net.URI;
import java.util.Map;
import org.junit.jupiter.api.Test;

public class HttpMessageTest {

  @Test
  public void marshallingIsReversible() {
    String originalBody = "Hello World!";
    HttpRequestMessage original = new HttpRequestMessage(
        Method.POST,
        URI.create("/a/b/c"),
        Map.of("Header1", "value1", "Header2", "value2"),
        originalBody
    );
    assertEquals(originalBody, HttpMessageParser.parseRequest(original.message()).body());
  }
}
