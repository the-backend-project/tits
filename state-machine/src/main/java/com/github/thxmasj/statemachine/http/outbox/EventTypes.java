package com.github.thxmasj.statemachine.http.outbox;

import com.github.thxmasj.statemachine.BasicEventType;
import com.github.thxmasj.statemachine.EventType;
import com.github.thxmasj.statemachine.message.http.HttpResponseMessage;
import java.util.UUID;

public interface EventTypes {

  EventType<HttpResponseMessage, Void> ResponseReceived = BasicEventType.of(
      "Response received",
      UUID.fromString("cd730efa-286d-4e29-b8bf-55df708fe889"),
      HttpResponseMessage.class,
      Void.class
  );
  EventType<Void, Void> TimeoutExpired = BasicEventType.of(
      "Timeout expired",
      UUID.fromString("7ff41071-2411-405d-acf2-c3b66823f17d"),
      Void.class,
      Void.class
  );
  EventType<Void, Void> Retry = BasicEventType.of(
      "Retry",
      UUID.fromString("61bd64d6-6b65-453e-8d60-7b4e01c5aa53"),
      Void.class,
      Void.class
  );

}
