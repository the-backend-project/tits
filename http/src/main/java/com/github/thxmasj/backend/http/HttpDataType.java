package com.github.thxmasj.backend.http;

import com.github.thxmasj.backend.core.DataType;
import java.util.Objects;

public class HttpDataType {

  public static DataType<HttpRequest<byte[]>> forRequest() {
    return new DataType<>() {
      @Override
      public String name() {
        return "HTTP request";
      }

      @Override
      public HttpRequest<byte[]> unmarshal(byte[] value) {
        return HttpMessageParser.parseRequest(value);
      }

      @Override
      public byte[] marshal(HttpRequest<byte[]> request) {
        return HttpMessage.toBytes(request);
      }
    };
  }

  public static <T> DataType<HttpRequest<T>> forRequest(DataType<T> payloadType) {
    return new RequestDataType<>(payloadType);
  }

  public static DataType<HttpResponse<byte[]>> forResponse() {
    return new DataType<>() {
      @Override
      public String name() {
        return "HTTP response";
      }

      @Override
      public HttpResponse<byte[]> unmarshal(byte[] value) {
        return HttpMessageParser.parseResponse(value);
      }

      @Override
      public byte[] marshal(HttpResponse<byte[]> response) {
        return HttpMessage.toBytes(response);
      }
    };
  }

  public static <T> DataType<HttpResponse<T>> forResponse(DataType<T> payloadType) {
    return new ResponseDataType<>(payloadType);
  }

  private static class RequestDataType<T> implements DataType<HttpRequest<T>> {

    private final DataType<T> payloadType;

    public RequestDataType(DataType<T> payloadType) {
      this.payloadType = payloadType;
    }

    @Override
    public String name() {
      return "HTTP request<" + payloadType.name() + ">";
    }

    @Override
    public HttpRequest<T> unmarshal(byte[] value) {
      return HttpMessageParser.parseRequest(value).map(payloadType::unmarshal);
    }

    @Override
    public byte[] marshal(HttpRequest<T> request) {
      return HttpMessage.toBytes(request.map(payloadType::marshal));
    }

    @Override
    public boolean equals(Object o) {
      if (!(o instanceof RequestDataType<?> that))
        return false;
      return Objects.equals(payloadType, that.payloadType);
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(payloadType);
    }
  }

  private static class ResponseDataType<T> implements DataType<HttpResponse<T>> {

    private final DataType<T> payloadType;

    public ResponseDataType(DataType<T> payloadType) {
      this.payloadType = payloadType;
    }

    @Override
    public String name() {
      return "HTTP response<" + payloadType.name() + ">";
    }

    @Override
    public HttpResponse<T> unmarshal(byte[] value) {
      return HttpMessageParser.parseResponse(value).map(payloadType::unmarshal);
    }

    @Override
    public byte[] marshal(HttpResponse<T> response) {
      return HttpMessage.toBytes(response.map(payloadType::marshal));
    }

    @Override
    public boolean equals(Object o) {
      if (!(o instanceof ResponseDataType<?> that))
        return false;
      return Objects.equals(payloadType, that.payloadType);
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(payloadType);
    }
  }
}
