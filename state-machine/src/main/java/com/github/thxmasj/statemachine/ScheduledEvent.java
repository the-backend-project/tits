package com.github.thxmasj.statemachine;

import java.time.Duration;

public record ScheduledEvent<I, O>(EventType<I, O> type, Duration deadline) {
}
