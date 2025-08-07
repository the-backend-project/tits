package com.github.thxmasj.statemachine.logging;

import java.util.List;
import java.util.Optional;
import com.github.thxmasj.statemachine.message.Message;

public interface LogMessageFormatter {

  static Optional<LogMessageFormatter> getFormatter(List<LogMessageFormatter> formatters, Message message) {
    for (LogMessageFormatter formatter : formatters) {
      if (formatter.isRelevantQueue(message) && formatter.isRelevantType(message)) {
        return Optional.of(formatter);
      }
    }
    return Optional.empty();
  }

  String format(String message);

  boolean isRelevantType(Message message);

  boolean isRelevantQueue(Message message);

}


