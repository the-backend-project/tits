package com.github.thxmasj.statemachine.http.inbox;

import com.github.thxmasj.statemachine.Validated;
import com.github.thxmasj.statemachine.message.http.HttpRequestMessage;
import java.util.function.Function;

public interface ContentParser<T> extends Function<HttpRequestMessage, Validated<T>> {
}
