package com.github.thxmasj.statemachine;

import java.time.Duration;

public record ScheduledEvent(EventType type, Duration deadline) {
}
