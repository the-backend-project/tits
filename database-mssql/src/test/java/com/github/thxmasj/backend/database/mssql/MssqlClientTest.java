package com.github.thxmasj.backend.database.mssql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.HexFormat;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MssqlClientTest {

  @Test
  void uuidIsStoredAsTheBytesOfItsStringForm() {
    UUID uuid = UUID.fromString("019a6b2c-1d3e-7f40-8a1b-2c3d4e5f6071");
    byte[] bytes = MssqlClient.bytes(uuid);
    assertEquals("019a6b2c1d3e7f408a1b2c3d4e5f6071", HexFormat.of().formatHex(bytes));
    assertEquals(uuid, MssqlClient.uuid(bytes));
  }

  @Test
  void timeOrderedUuidsAreOrderedAsBinaries() {
    // SQL Server compares BINARY values as unsigned bytes from the first. These are version 7 UUIDs a millisecond
    // apart, with random bits that would order them the other way.
    UUID earlier = UUID.fromString("018bcfe5-6800-7fff-bfff-ffffffffffff");
    UUID later = UUID.fromString("018bcfe5-6801-7000-8000-000000000000");
    assertTrue(Arrays.compareUnsigned(MssqlClient.bytes(earlier), MssqlClient.bytes(later)) < 0);
  }

  @Test
  void duplicateKeyGetsItsLeadingBinaryAsUuid() {
    assertEquals(
        "019a6b2c-1d3e-7f40-8a1b-2c3d4e5f6071, 1",
        MssqlClient.withUuid("0x019A6B2C1D3E7F408A1B2C3D4E5F6071, 1")
    );
    assertEquals(
        "019a6b2c-1d3e-7f40-8a1b-2c3d4e5f6071",
        MssqlClient.withUuid("0x019A6B2C1D3E7F408A1B2C3D4E5F6071")
    );
    assertEquals(
        "019a6b2c-1d3e-7f40-8a1b-2c3d4e5f6071, 0x7B22",
        MssqlClient.withUuid("0x019A6B2C1D3E7F408A1B2C3D4E5F6071, 0x7B22")
    );
    assertEquals("0x7B22, 1", MssqlClient.withUuid("0x7B22, 1"));
  }

}
