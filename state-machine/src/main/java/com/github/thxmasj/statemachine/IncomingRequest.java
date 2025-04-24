package com.github.thxmasj.statemachine;

import com.github.thxmasj.statemachine.message.http.HttpRequestMessage;
import java.util.function.BiFunction;

public record IncomingRequest(
    EventTrigger eventTrigger,
    String clientId,
    BiFunction<EntityId, EventType, String> messageId,
    byte[] digest,
    HttpRequestMessage requestMessage,
    IncomingRequestValidator<?> validator
) {}
