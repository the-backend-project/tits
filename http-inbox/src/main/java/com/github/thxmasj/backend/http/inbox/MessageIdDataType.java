package com.github.thxmasj.backend.http.inbox;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;

import com.github.thxmasj.backend.core.DataType;
import com.github.thxmasj.backend.http.inbox.HttpInbox.MessageId;
import java.util.List;

/// Marshals a message id as its client id and value in UTF-8, each prefixed with its length. Unlike JSON, it needs no
/// reflection. Message ids are unique by their marshalled form, so equal message ids always marshal to the same bytes.
public class MessageIdDataType implements DataType<MessageId> {

  @Override
  public String name() {
    return "message id";
  }

  @Override
  public MessageId unmarshal(byte[] value) {
    List<byte[]> parts = DataType.split(value);
    if (parts.size() != 2) {
      throw new IllegalArgumentException("Not a message id: " + parts.size() + " parts");
    }
    return new MessageId(new String(parts.getFirst(), UTF_8), new String(parts.get(1), UTF_8));
  }

  @Override
  public byte[] marshal(MessageId value) {
    // DataType.concat skips nulls, which would make the parts ambiguous
    return DataType.concat(
        requireNonNull(value.clientId(), "clientId").getBytes(UTF_8),
        requireNonNull(value.value(), "value").getBytes(UTF_8)
    );
  }

  @Override
  public boolean equals(Object obj) {
    return obj instanceof MessageIdDataType;
  }

  @Override
  public int hashCode() {
    return MessageIdDataType.class.hashCode();
  }
}
