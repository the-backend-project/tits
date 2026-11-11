package com.github.thxmasj.backend.http.inbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.github.thxmasj.backend.http.inbox.HttpInbox.MessageId;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

public class MessageIdDataTypeTest {

  private final MessageIdDataType dataType = new MessageIdDataType();

  @Test
  public void marshallingAndUnmarshalling() {
    MessageId original = new MessageId("client-æøå", "f721bf54-891b-41a0-aa63-5d3cbde52437/42");

    assertEquals(original, dataType.unmarshal(dataType.marshal(original)));
  }

  @Test
  public void emptyParts() {
    MessageId original = new MessageId("", "");

    assertEquals(original, dataType.unmarshal(dataType.marshal(original)));
  }

  @Test
  public void partsAreNotAmbiguous() {
    assertNotEquals(
        Arrays.toString(dataType.marshal(new MessageId("ab", "c"))),
        Arrays.toString(dataType.marshal(new MessageId("a", "bc")))
    );
  }
}
