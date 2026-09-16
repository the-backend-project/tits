package com.github.thxmasj.statemachine.http;

import com.github.thxmasj.statemachine.DataType;
import com.github.thxmasj.statemachine.message.http.HttpMessageParser;
import com.github.thxmasj.statemachine.message.http.HttpRequestMessage;
import com.github.thxmasj.statemachine.message.http.HttpResponseMessage;

public class HttpDataType {

  public static DataType<HttpRequestMessage> forRequest() {
    return new DataType<>() {
      @Override
      public String name() {
        return "HTTP request";
      }

      @Override
      public HttpRequestMessage unmarshal(byte[] value) {
        return HttpMessageParser.parseRequest(new String(value));
      }

      @Override
      public byte[] marshal(HttpRequestMessage request) {
        return request.toString().getBytes();
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
        return response.message().getBytes();
      }
    };
  }

}
