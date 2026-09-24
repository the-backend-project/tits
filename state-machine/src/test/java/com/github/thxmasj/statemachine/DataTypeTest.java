package com.github.thxmasj.statemachine;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.github.thxmasj.statemachine.Tuples.Tuple2;
import com.github.thxmasj.statemachine.http.HttpDataType;
import com.github.thxmasj.statemachine.message.http.HttpRequestMessage.Method;
import com.github.thxmasj.statemachine.message.http.TypedHttpRequest;
import java.net.URI;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

public class DataTypeTest {

  record TestPayload(String message, int count) {}

  @Test
  public void tuple2WithHttpRequestAndUuidMarshallingAndUnmarshalling() {
    DataType<Tuple2<TypedHttpRequest<TestPayload>, UUID>> dataType = DataType.tuple(
        HttpDataType.forRequest(DataType.json(TestPayload.class)),
        DataType.uuid()
    );

    TypedHttpRequest<TestPayload> request = new TypedHttpRequest<>(
        Method.POST,
        URI.create("/api/v1/resource"),
        Map.of("Content-Type", "application/json", "X-Request-ID", "12345"),
        new TestPayload("Hello World", 42)
    );
    UUID uuid = UUID.randomUUID();
    Tuple2<TypedHttpRequest<TestPayload>, UUID> original = Tuples.tuple(request, uuid);

    byte[] marshalled = dataType.marshal(original);
    Tuple2<TypedHttpRequest<TestPayload>, UUID> unmarshalled = dataType.unmarshal(marshalled);

    assertEquals(original, unmarshalled);
  }

  @Test
  public void tuple2WithHttpRequestStringPayloadAndUuidMarshallingAndUnmarshalling() {
    DataType<Tuple2<TypedHttpRequest<String>, UUID>> dataType = DataType.tuple(
        HttpDataType.forRequest(DataType.string()),
        DataType.uuid()
    );

    TypedHttpRequest<String> request = new TypedHttpRequest<>(
        Method.PUT,
        URI.create("https://example.com/api/test"),
        Map.of("Authorization", "Bearer token123"),
        "payload-data"
    );
    UUID uuid = UUID.randomUUID();
    Tuple2<TypedHttpRequest<String>, UUID> original = Tuples.tuple(request, uuid);

    byte[] marshalled = dataType.marshal(original);
    Tuple2<TypedHttpRequest<String>, UUID> unmarshalled = dataType.unmarshal(marshalled);

    assertEquals(original, unmarshalled);
  }
}
