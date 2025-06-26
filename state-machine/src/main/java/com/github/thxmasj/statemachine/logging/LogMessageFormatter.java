package com.github.thxmasj.statemachine.logging;

import java.util.List;
import java.util.Optional;
import com.github.thxmasj.statemachine.Notification;

public interface LogMessageFormatter {

  static Optional<LogMessageFormatter> getFormatter(List<LogMessageFormatter> formatters, Notification notification) {
    for (LogMessageFormatter formatter : formatters) {
      if (formatter.isRelevantQueue(notification) && formatter.isRelevantType(notification)) {
        return Optional.of(formatter);
      }
    }
    return Optional.empty();
  }

  String format(String message);

  boolean isRelevantType(Notification notification);

  boolean isRelevantQueue(Notification notification);

}


