package com.github.thxmasj.backend.templates.cardpayment;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.github.thxmasj.backend.core.GroupedDataType;
import java.nio.ByteBuffer;
import java.util.Arrays;

/// Batch numbers grouped by merchant id and ordered by number: the merchant id, a 0 byte, and the number as 8 bytes,
/// big-endian. Numbers are not negative, so they order as unsigned bytes.
public class BatchNumberDataType implements GroupedDataType<BatchNumber, String> {

  @Override
  public String name() {
    return "BatchNumber";
  }

  @Override
  public byte[] marshalGroup(String merchantId) {
    byte[] merchant = merchantId.getBytes(UTF_8);
    return Arrays.copyOf(merchant, merchant.length + 1);
  }

  @Override
  public byte[] marshal(BatchNumber value) {
    byte[] group = marshalGroup(value.merchantId());
    return ByteBuffer.allocate(group.length + Long.BYTES).put(group).putLong(value.number()).array();
  }

  @Override
  public BatchNumber unmarshal(byte[] value) {
    int merchantLength = value.length - Long.BYTES - 1;
    return new BatchNumber(
        new String(value, 0, merchantLength, UTF_8),
        ByteBuffer.wrap(value, merchantLength + 1, Long.BYTES).getLong()
    );
  }

}
