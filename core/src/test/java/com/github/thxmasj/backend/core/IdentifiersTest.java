package com.github.thxmasj.backend.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class IdentifiersTest {

  @Test
  void idIsVersion7WithTheTime() {
    long epochMillis = 1_700_000_000_123L;
    UUID id = Identifiers.newId(epochMillis);
    assertEquals(7, id.version());
    assertEquals(2, id.variant());
    assertEquals(epochMillis, id.getMostSignificantBits() >>> 16);
    assertEquals(String.format("%012x", epochMillis), id.toString().replace("-", "").substring(0, 12));
  }

  @Test
  void idsAreOrderedByTime() {
    UUID earlier = Identifiers.newId(1_700_000_000_000L);
    UUID later = Identifiers.newId(1_700_000_000_001L);
    assertTrue(earlier.toString().compareTo(later.toString()) < 0);
  }

  @Test
  void idsOfTheSameTimeDiffer() {
    assertNotEquals(Identifiers.newId(1_700_000_000_000L), Identifiers.newId(1_700_000_000_000L));
  }

}
