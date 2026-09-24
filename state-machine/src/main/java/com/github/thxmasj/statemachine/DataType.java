package com.github.thxmasj.statemachine;

import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.github.thxmasj.statemachine.Tuples.Tuple2;
import com.github.thxmasj.statemachine.Tuples.Tuple3;
import com.github.thxmasj.statemachine.Tuples.Tuple4;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public interface DataType<T> {

  String name();

  T unmarshal(byte[] value);

  byte[] marshal(T value);

  ObjectMapper objectMapper = new ObjectMapper()
      .registerModule(new JavaTimeModule())
      .configure(DeserializationFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE, false)
      .configure(JsonParser.Feature.INCLUDE_SOURCE_IN_LOCATION, true)
      .setSerializationInclusion(Include.NON_NULL);

  static DataType<Void> none() {
    return new NoneDataType();
  }

  static <I> DataType<I> unknown() {
    return new UnknownDataType<>();
  }

  static DataType<UUID> uuid() {
    return new UUIDDataType();
  }

  static DataType<String> string() {
    return new StringDataType();
  }

  static DataType<byte[]> binary() {
    return new BinaryDataType();
  }

  static <T> DataType<T> json(Class<T> clazz) {
    return new JsonDataType<>(clazz);
  }

  static <T1, T2> DataType<Tuple2<T1, T2>> tuple(DataType<T1> t1, DataType<T2> t2) {
    return new Tuple2DataType<>(t1, t2);
  }

  static <T1, T2, T3> DataType<Tuple3<T1, T2, T3>> tuple(DataType<T1> t1, DataType<T2> t2, DataType<T3> t3) {
    return new Tuple3DataType<>(t1, t2, t3);
  }

  static <T1, T2, T3, T4> DataType<Tuple4<T1, T2, T3, T4>> tuple(DataType<T1> t1, DataType<T2> t2, DataType<T3> t3, DataType<T4> t4) {
    return new Tuple4DataType<>(t1, t2, t3, t4);
  }

  class JsonDataType<T> implements DataType<T> {

    private final Class<T> clazz;

    public JsonDataType(Class<T> clazz) {this.clazz = clazz;}

    @Override
    public String name() {
      return "json<" + clazz.getSimpleName() + ">";
    }

    @Override
    public T unmarshal(byte[] value) {
      try {
        return objectMapper.readerFor(clazz).readValue(value);
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }

    @Override
    public byte[] marshal(T value) {
      try {
        return objectMapper.writeValueAsBytes(value);
      } catch (JsonProcessingException e) {
        throw new RuntimeException(e);
      }
    }

    @Override
    public boolean equals(Object o) {
      if (!(o instanceof JsonDataType<?> that))
        return false;
      return Objects.equals(clazz, that.clazz);
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(clazz);
    }
  }

  class BinaryDataType implements DataType<byte[]> {

    @Override
    public String name() {
      return "binary";
    }

    @Override
    public byte[] unmarshal(byte[] value) {
      return value;
    }

    @Override
    public byte[] marshal(byte[] value) {
      return value;
    }

    @Override
    public boolean equals(Object obj) {
      return obj instanceof BinaryDataType;
    }
  }

  class StringDataType implements DataType<String> {

    @Override
    public String name() {
      return "string";
    }

    @Override
    public String unmarshal(byte[] value) {
      return new String(value);
    }

    @Override
    public byte[] marshal(String value) {
      return value.getBytes();
    }

    @Override
    public boolean equals(Object obj) {
      return obj instanceof StringDataType;
    }

  }

  class UUIDDataType implements DataType<UUID> {

    @Override
    public String name() {
      return "uuid";
    }

    @Override
    public UUID unmarshal(byte[] value) {
      if (value.length != 16) {
        throw new IllegalArgumentException("Byte array must be exactly 16 bytes long, got " + value.length);
      }
      ByteBuffer buffer = ByteBuffer.wrap(value);
      long mostSigBits = buffer.getLong();
      long leastSigBits = buffer.getLong();
      return new UUID(mostSigBits, leastSigBits);
    }

    @Override
    public byte[] marshal(UUID value) {
      return ByteBuffer.allocate(16)
          .putLong(value.getMostSignificantBits())
          .putLong(value.getLeastSignificantBits())
          .array();
    }

    @Override
    public boolean equals(Object obj) {
      return obj instanceof UUIDDataType;
    }
  }

  class UnknownDataType<I> implements DataType<I> {

    @Override
    public String name() {
      return "?";
    }

    @Override
    public I unmarshal(byte[] value) {
      return null;
    }

    @Override
    public byte[] marshal(I value) {
      return new byte[0];
    }

    @Override
    public boolean equals(Object obj) {
      return obj instanceof UnknownDataType;
    }
  }

  class NoneDataType implements DataType<Void> {
    @Override
    public String name() {
      return "-";
    }

    @Override
    public Void unmarshal(byte[] value) {
      return null;
    }

    @Override
    public byte[] marshal(Void value) {
      return null;
    }

    @Override
    public boolean equals(Object o) {
      return o instanceof NoneDataType;
    }

  }

  class Tuple2DataType<T1, T2> implements DataType<Tuple2<T1, T2>> {

    private final DataType<T1> t1;
    private final DataType<T2> t2;

    public Tuple2DataType(DataType<T1> t1, DataType<T2> t2) {
      this.t1 = t1;
      this.t2 = t2;
    }

    @Override
    public String name() {
      return String.format("(%s, %s)", t1.name(), t2.name());
    }

    @Override
    public Tuple2<T1, T2> unmarshal(byte[] value) {
      List<byte[]> splitValue = DataType.split(value);
      return Tuples.tuple(t1.unmarshal(splitValue.getFirst()), t2.unmarshal(splitValue.get(1)));
    }

    @Override
    public byte[] marshal(Tuple2<T1, T2> value) {
      return concat(t1.marshal(value.t1()), t2.marshal(value.t2()));
    }

    @Override
    public boolean equals(Object o) {
      if (!(o instanceof Tuple2DataType<?, ?> that))
        return false;
      return Objects.equals(t1, that.t1) && Objects.equals(t2, that.t2);
    }

    @Override
    public int hashCode() {
      return Objects.hash(t1, t2);
    }
  }

  class Tuple3DataType<T1, T2, T3> implements DataType<Tuple3<T1, T2, T3>> {

    private final DataType<T1> t1;
    private final DataType<T2> t2;
    private final DataType<T3> t3;

    public Tuple3DataType(DataType<T1> t1, DataType<T2> t2, DataType<T3> t3) {
      this.t1 = t1;
      this.t2 = t2;
      this.t3 = t3;
    }

    @Override
    public String name() {
      return String.format("(%s, %s, %s)", t1.name(), t2.name(), t3.name());
    }

    @Override
    public Tuple3<T1, T2, T3> unmarshal(byte[] value) {
      List<byte[]> splitValue = DataType.split(value);
      return Tuples.tuple(
          t1.unmarshal(splitValue.getFirst()),
          t2.unmarshal(splitValue.get(1)),
          t3.unmarshal(splitValue.get(2))
      );
    }

    @Override
    public byte[] marshal(Tuple3<T1, T2, T3> value) {
      return concat(t1.marshal(value.t1()), t2.marshal(value.t2()), t3.marshal(value.t3()));
    }

    @Override
    public boolean equals(Object o) {
      if (!(o instanceof Tuple3DataType<?, ?, ?> that))
        return false;
      return Objects.equals(t1, that.t1) && Objects.equals(t2, that.t2) && Objects.equals(t3, that.t3);
    }

    @Override
    public int hashCode() {
      return Objects.hash(t1, t2, t3);
    }
  }

  class Tuple4DataType<T1, T2, T3, T4> implements DataType<Tuple4<T1, T2, T3, T4>> {

    private final DataType<T1> t1;
    private final DataType<T2> t2;
    private final DataType<T3> t3;
    private final DataType<T4> t4;

    public Tuple4DataType(DataType<T1> t1, DataType<T2> t2, DataType<T3> t3, DataType<T4> t4) {
      this.t1 = t1;
      this.t2 = t2;
      this.t3 = t3;
      this.t4 = t4;
    }

    @Override
    public String name() {
      return String.format("(%s, %s, %s)", t1.name(), t2.name(), t3.name());
    }

    @Override
    public Tuple4<T1, T2, T3, T4> unmarshal(byte[] value) {
      List<byte[]> splitValue = DataType.split(value);
      return Tuples.tuple(
          t1.unmarshal(splitValue.getFirst()),
          t2.unmarshal(splitValue.get(1)),
          t3.unmarshal(splitValue.get(2)),
          t4.unmarshal(splitValue.get(3))
      );
    }

    @Override
    public byte[] marshal(Tuple4<T1, T2, T3, T4> value) {
      return concat(t1.marshal(value.t1()), t2.marshal(value.t2()), t3.marshal(value.t3()), t4.marshal(value.t4()));
    }

    @Override
    public boolean equals(Object o) {
      if (!(o instanceof Tuple4DataType<?, ?, ?, ?> that))
        return false;
      return Objects.equals(t1, that.t1) && Objects.equals(t2, that.t2) && Objects.equals(t3, that.t3)
          && Objects.equals(t4, that.t4);
    }

    @Override
    public int hashCode() {
      return Objects.hash(t1, t2, t3, t4);
    }
  }

  static byte[] concat(byte[]... arrays) {
    int totalLength = 0;
    for (byte[] array : arrays) {
      if (array != null) {
        if (array.length > 65535) {
          throw new IllegalArgumentException("Array length exceeds 65535: " + array.length);
        }
        totalLength += 2 + array.length; // 2 bytes for length + array data
      }
    }
    ByteBuffer buffer = ByteBuffer.allocate(totalLength);
    for (byte[] array : arrays) {
      if (array != null) {
        int len = array.length;
        // Write 2-byte unsigned length (Big-Endian)
        buffer.put((byte) (len >>> 8));
        buffer.put((byte) len);
        // Write the actual array bytes
        buffer.put(array);
      }
    }
    return buffer.array();
  }

  static List<byte[]> split(byte[] concatenated) {
    List<byte[]> result = new ArrayList<>();
    ByteBuffer buffer = ByteBuffer.wrap(concatenated);

    while (buffer.hasRemaining()) {
      // Read 2-byte length as an unsigned integer
      int high = buffer.get() & 0xFF;
      int low = buffer.get() & 0xFF;
      int len = (high << 8) | low;

      // Read the exact number of bytes for this array
      byte[] array = new byte[len];
      buffer.get(array);
      result.add(array);
    }

    return result;
  }

}
