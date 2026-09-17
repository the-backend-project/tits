package com.github.thxmasj.statemachine.message.http;

public record TypedHttpRequest<T>(HttpRequestMessage message, T payload) {}
