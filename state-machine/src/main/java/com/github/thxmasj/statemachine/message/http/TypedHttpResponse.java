package com.github.thxmasj.statemachine.message.http;

public record TypedHttpResponse<T>(HttpResponseMessage message, T payload) {}
