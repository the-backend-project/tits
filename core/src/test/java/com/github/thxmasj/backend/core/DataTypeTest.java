package com.github.thxmasj.backend.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.github.thxmasj.backend.core.Tuples.Tuple2;
import java.util.UUID;
import org.junit.jupiter.api.Test;

public class DataTypeTest {

  @Test
  public void eventReferenceMarshallingAndUnmarshalling() {
    EventReference original = new EventReference(UUID.randomUUID(), 42);

    byte[] marshalled = DataType.eventReference().marshal(original);

    assertEquals(20, marshalled.length);
    assertEquals(original, DataType.eventReference().unmarshal(marshalled));
  }

  @Test
  public void nullEventReferenceInTuple() {
    DataType<Tuple2<String, EventReference>> dataType = DataType.tuple(DataType.string(), DataType.eventReference());
    Tuple2<String, EventReference> original = Tuples.tuple("Nothing to roll back", null);

    assertEquals(original, dataType.unmarshal(dataType.marshal(original)));
  }
}
