package com.github.thxmasj.backend.http.outbox;

import com.github.thxmasj.backend.core.*;
import com.github.thxmasj.backend.core.http.*;
import com.github.thxmasj.backend.core.message.http.*;
import reactor.core.publisher.*;

import java.net.*;
import java.time.ZonedDateTime;

import static com.github.thxmasj.backend.http.outbox.EventTypes.*;

class ForwardAction implements Action<HttpRequestMessage> {

  private final HttpClient forwarder;

  public ForwardAction(HttpClient forwarder) {
    this.forwarder = forwarder;
  }

  @Override
  public String name() {return "Forward";}

  @Override
  public Mono<InputEvent<?>> execute(HttpRequestMessage request) {
    System.out.println(ZonedDateTime.now() + ": Forwarding " + request.requestLine());
    return forwarder.exchange(request)
        .<InputEvent<?>>map(response -> new InputEvent<>(ResponseReceived, response))
        .onErrorResume(ConnectException.class, _ -> Mono.just(new InputEvent<>(ConnectionFailed, null)));
  }
}
