package com.github.thxmasj.statemachine;

import static java.util.stream.Collectors.toMap;

import com.github.thxmasj.statemachine.http.HttpClient;
import com.github.thxmasj.statemachine.message.http.HttpRequestMessage;
import com.github.thxmasj.statemachine.message.http.HttpResponseMessage;
import io.netty.handler.codec.http.HttpMethod;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.Map.Entry;
import java.util.function.Supplier;
import reactor.core.publisher.Mono;
import reactor.netty.ByteBufFlux;

public class NettyHttpClient implements HttpClient {

  private final reactor.netty.http.client.HttpClient nettyHttpClient;
  private final Supplier<Map<String, String>> fixedHeaders;

  public NettyHttpClient(reactor.netty.http.client.HttpClient nettyHttpClient) {
    this.nettyHttpClient = nettyHttpClient;
    this.fixedHeaders = Map::of;
  }

  public NettyHttpClient(reactor.netty.http.client.HttpClient nettyHttpClient, Supplier<String> accessToken) {
    this.nettyHttpClient = nettyHttpClient;
    this.fixedHeaders = () -> Map.of("Authorization", "Bearer " + accessToken.get());
  }

  public NettyHttpClient(reactor.netty.http.client.HttpClient nettyHttpClient, String clientId, String clientSecret) {
    this.nettyHttpClient = nettyHttpClient;
    this.fixedHeaders = () -> Map.of("Authorization", "Basic " + Base64.getEncoder().encodeToString((clientId + ":" + clientSecret).getBytes()));
  }

  @Override
  public Mono<HttpResponseMessage> exchange(HttpRequestMessage message) {
    long t0 = System.nanoTime();
    return nettyHttpClient
        .headers(h -> message.headers().forEach(h::add))
        .headers(h -> fixedHeaders.get().forEach(h::add))
        .request(switch (message.method()) {
          case GET -> HttpMethod.GET;
          case POST -> HttpMethod.POST;
          case PUT -> HttpMethod.PUT;
          case DELETE -> HttpMethod.DELETE;
        })
        .uri(message.uri())
        .send(message.body() == null ? Mono.empty() : ByteBufFlux.fromString(Mono.just(message.body())))
        .response((httpClientResponse, buf) -> buf.aggregate().asString()
            .map(body -> new HttpResponseMessage(
                    httpClientResponse.status().code(),
                    httpClientResponse.status().reasonPhrase(),
                    httpClientResponse.responseHeaders().entries().stream().collect(toMap(Entry::getKey, Entry::getValue)),
                    body
                )
            )
          .defaultIfEmpty(new HttpResponseMessage(
              httpClientResponse.status().code(),
              httpClientResponse.status().reasonPhrase(),
              httpClientResponse.responseHeaders().entries().stream().collect(toMap(Entry::getKey, Entry::getValue))
          ))
        )
        .single()
        .onErrorMap(
            io.netty.handler.timeout.TimeoutException.class,
            t -> new TimeoutException(Duration.ofNanos(System.nanoTime() - t0), t)
        )
        .onErrorMap(
            io.netty.handler.timeout.ReadTimeoutException.class,
            t -> new TimeoutException(Duration.ofNanos(System.nanoTime() - t0), t)
        );
  }

}
