package com.github.thxmasj.statemachine.http;

import com.github.thxmasj.statemachine.DataType;
import com.github.thxmasj.statemachine.message.http.HttpMessageParser;
import com.github.thxmasj.statemachine.message.http.HttpRequestMessage;
import com.github.thxmasj.statemachine.message.http.HttpResponseMessage;
import com.github.thxmasj.statemachine.message.http.TypedHttpRequest;
import com.github.thxmasj.statemachine.message.http.TypedHttpResponse;

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
    return new DataType<>() {
      @Override
      public String name() {
        return "HTTP request";
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

    };
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
    return new DataType<>() {
      @Override
      public String name() {
        return "HTTP response";
      }

      @Override
      public TypedHttpResponse<T> unmarshal(byte[] value) {
        HttpResponseMessage response = HttpMessageParser.parseResponse(value);
        return new TypedHttpResponse<>(response, payloadType.unmarshal(response.body()));
      }

      @Override
      public byte[] marshal(TypedHttpResponse<T> response) {
        return response.message().toBytes();
      }

    };
  }

}
