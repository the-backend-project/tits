package com.github.thxmasj.statemachine.http;

import com.github.thxmasj.statemachine.DataType;
import com.github.thxmasj.statemachine.message.http.HttpMessageParser;
import com.github.thxmasj.statemachine.message.http.HttpRequestMessage;
import com.github.thxmasj.statemachine.message.http.HttpResponseMessage;
import com.github.thxmasj.statemachine.message.http.TypedHttpRequest;
import com.github.thxmasj.statemachine.message.http.TypedHttpResponse;
import java.util.Objects;

public class HttpDataType {

  public static DataType<HttpRequestMessage> forRequest() {
    return new DataType<>() {
      @Override
      public String name() {
        return "HTTP request";
      }

      @Override
      public HttpRequestMessage unmarshal(byte[] value) {
        return HttpMessageParser.parseRequest(value);
      }

      @Override
      public byte[] marshal(HttpRequestMessage request) {
        return request.toBytes();
      }
    };
  }

  public static <T> DataType<TypedHttpRequest<T>> forRequest(DataType<T> payloadType) {
    return new TypedHttpRequestDataType<>(payloadType);
  }

  public static DataType<HttpResponseMessage> forResponse() {
    return new DataType<>() {
      @Override
      public String name() {
        return "HTTP response";
      }

      @Override
      public HttpResponseMessage unmarshal(byte[] value) {
        return HttpMessageParser.parseResponse(value);
      }

      @Override
      public byte[] marshal(HttpResponseMessage response) {
        return response.toBytes();
      }
    };
  }

  public static <T> DataType<TypedHttpResponse<T>> forResponse(DataType<T> payloadType) {
    return new TypedHttpResponseDataType<>(payloadType);
  }

  private static class TypedHttpRequestDataType<T> implements DataType<TypedHttpRequest<T>> {

    private final DataType<T> payloadType;

    public TypedHttpRequestDataType(DataType<T> payloadType) {
      this.payloadType = payloadType;
    }

    @Override
    public String name() {
      return "HTTP request<" + payloadType.name() + ">";
    }

    @Override
    public TypedHttpRequest<T> unmarshal(byte[] value) {
      HttpRequestMessage request = HttpMessageParser.parseRequest(value);
      return new TypedHttpRequest<>(request.method(), request.uri(), request.headers(), payloadType.unmarshal(request.body()));
    }

    @Override
    public byte[] marshal(TypedHttpRequest<T> request) {
      HttpRequestMessage r = new HttpRequestMessage(request.method(), request.uri(), request.headers(), payloadType.marshal(request.payload()));
      return r.toBytes();
    }

    @Override
    public boolean equals(Object o) {
      if (!(o instanceof TypedHttpRequestDataType<?> that))
        return false;
      return Objects.equals(payloadType, that.payloadType);
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(payloadType);
    }
  }

  private static class TypedHttpResponseDataType<T> implements DataType<TypedHttpResponse<T>> {

    private final DataType<T> payloadType;

    public TypedHttpResponseDataType(DataType<T> payloadType) {
      this.payloadType = payloadType;
    }

    @Override
    public String name() {
      return "HTTP response<" + payloadType.name() + ">";
    }

    @Override
    public TypedHttpResponse<T> unmarshal(byte[] value) {
      HttpResponseMessage response = HttpMessageParser.parseResponse(value);
      return new TypedHttpResponse<>(response.statusCode(), response.reasonPhrase(), response.headers(), payloadType.unmarshal(response.body()));
    }

    @Override
    public byte[] marshal(TypedHttpResponse<T> response) {
      HttpResponseMessage r = new HttpResponseMessage(response.statusCode(), response.reasonPhrase(), response.headers(), payloadType.marshal(response.payload()));
      return r.toBytes();
    }

    @Override
    public boolean equals(Object o) {
      if (!(o instanceof TypedHttpResponseDataType<?> that))
        return false;
      return Objects.equals(payloadType, that.payloadType);
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(payloadType);
    }
  }
}
