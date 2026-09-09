package com.github.thxmasj.statemachine.http.outbox;

import static com.github.thxmasj.statemachine.BasicEventType.of;

import com.github.thxmasj.statemachine.BasicEventType;
import com.github.thxmasj.statemachine.EntityModel;
import com.github.thxmasj.statemachine.EventType;
import com.github.thxmasj.statemachine.message.http.HttpResponseMessage;
import java.util.UUID;

public interface EventTypes {

  EventType<EntityModel, EntityModel> ServiceUnavailable = of("ServiceUnavailable", UUID.fromString("f91e560f-ba7b-4e14-976a-82e592a627f5"), EntityModel.class);
  EventType<String, String>
      InvalidResponse = of("InvalidResponse", UUID.fromString("450679ab-bc60-46cb-bb97-d171c34c2750"), String.class);

  EventType<HttpResponseMessage, Void> ResponseReceived = BasicEventType.of(
      "Response received",
      UUID.fromString("cd730efa-286d-4e29-b8bf-55df708fe889"),
      HttpResponseMessage.class,
      Void.class
  );
  EventType<Void, Void> TimeoutExpired = BasicEventType.of(
      "Timeout expired",
      UUID.fromString("7ff41071-2411-405d-acf2-c3b66823f17d")
  );
  EventType<Void, Void> Retry = BasicEventType.of(
      "Retry",
      UUID.fromString("61bd64d6-6b65-453e-8d60-7b4e01c5aa53")
  );
  EventType<Void, Void> ConnectionFailed = BasicEventType.of(
      "Connection failed",
      UUID.fromString("14be9085-1f7c-427c-9963-2c72cdc0888f")
  );
  EventType<Void, Void> ConnectionDropped = BasicEventType.of(
      "Connection dropped",
      UUID.fromString("94ba4ab3-3b87-4ceb-9fd3-0e8ab0c21a34")
  );

}
