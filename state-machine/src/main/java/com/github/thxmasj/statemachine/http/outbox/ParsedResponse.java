package com.github.thxmasj.statemachine.http.outbox;

import com.github.thxmasj.statemachine.*;
import com.github.thxmasj.statemachine.message.http.*;

public record ParsedResponse<T>(
    HttpResponseMessage message,
    Validated<T> parsedBody
) {}
