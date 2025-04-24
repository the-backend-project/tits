package com.github.thxmasj.statemachine;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.ZonedDateTime;

public record DelaySpecification(Duration minimum, Duration maximum, Duration totalMaximum, double powerBase) {

  public Duration calculateDelay(int attempts) {
    if (attempts == 0)
      return minimum;
    Duration delay = exponential(minimum, powerBase, attempts);
    if (delay.isNegative() || delay.compareTo(maximum) > 0) {
      return maximum;
    }
    return delay;
  }

  public boolean isExhausted(ZonedDateTime enqueueTime, ZonedDateTime nextAttempt, Clock clock) {
    return enqueueTime.plus(totalMaximum).isBefore(max(nextAttempt, ZonedDateTime.now(clock)));
  }

  private ZonedDateTime max(ZonedDateTime t1, ZonedDateTime t2) {
    return t1.compareTo(t2) < 0 ? t2 : t1;
  }

  private Duration exponential(Duration minimum, double powerBase, int times) {
    BigDecimal minimumInSeconds = BigDecimal.valueOf(minimum.getSeconds())
        .add(BigDecimal.valueOf(minimum.getNano(), 9));
    double poweredValue = Math.pow(powerBase, times);
    BigDecimal result;
    if (poweredValue >= (double) Long.MAX_VALUE) {
      result = BigDecimal.valueOf(Long.MAX_VALUE);
    } else {
      result = minimumInSeconds.multiply(BigDecimal.valueOf(poweredValue));
    }
    return Duration.ofSeconds(result.longValue(), result.remainder(BigDecimal.ONE).movePointRight(9).intValue());
  }

}
