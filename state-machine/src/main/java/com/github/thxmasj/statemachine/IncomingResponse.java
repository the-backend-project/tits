package com.github.thxmasj.statemachine;

import com.github.thxmasj.statemachine.message.http.HttpResponseMessage;

public record IncomingResponse(
    EntityId entityId,
    HttpResponseMessage responseMessage,
    IncomingResponseValidator<?> validator
) {}
