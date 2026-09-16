package com.github.thxmasj.statemachine;

import static com.github.thxmasj.statemachine.Tuples.tuple;

import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.github.thxmasj.statemachine.Tuples.Tuple2;
import com.github.thxmasj.statemachine.Tuples.Tuple3;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public interface DataType<T> {

  String name();

  T unmarshal(byte[] value);

  byte[] marshal(T value);

  ObjectMapper objectMapper = new ObjectMapper()
      .registerModule(new JavaTimeModule())
      .configure(DeserializationFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE, false)
      .setSerializationInclusion(Include.NON_NULL);

  static DataType<Void> none() {
    return new DataType<>() {
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
    };
  }

  static <I> DataType<I> unknown() {
    return new DataType<>() {
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
    };
  }

  static DataType<UUID> forUUID() {
    return new DataType<>() {
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
        return new UUID(mostSigBits, leastSigBits);      }

      @Override
      public byte[] marshal(UUID value) {
        return ByteBuffer.allocate(16)
            .putLong(value.getMostSignificantBits())
            .putLong(value.getLeastSignificantBits())
            .array();      }
    };
  }

  static DataType<String> forString() {
    return new DataType<>() {
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
    };
  }

  static <T> DataType<T> forClass(Class<T> clazz) {
    return new DataType<>() {
      @Override
      public String name() {
        return clazz.getSimpleName();
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
    };
  }

  static <T1, T2> DataType<Tuple2<T1, T2>> forTuple(DataType<T1> t1, DataType<T2> t2) {
    return new DataType<>() {
      @Override
      public String name() {
        return String.format("(%s, %s)", t1.name(), t2.name());
      }

      @Override
      public Tuple2<T1, T2> unmarshal(byte[] value) {
        List<byte[]> splitValue = DataType.split(value);
        return tuple(t1.unmarshal(splitValue.getFirst()), t2.unmarshal(splitValue.get(1)));
      }

      @Override
      public byte[] marshal(Tuple2<T1, T2> value) {
        return concat(t1.marshal(value.t1()), t2.marshal(value.t2()));
      }
    };

  }

  static <T1, T2, T3> DataType<Tuple3<T1, T2, T3>> forTuple(DataType<T1> t1, DataType<T2> t2, DataType<T3> t3) {
    return new DataType<>() {
      @Override
      public String name() {
        return String.format("(%s, %s, %s)", t1.name(), t2.name(), t3.name());
      }

      @Override
      public Tuple3<T1, T2, T3> unmarshal(byte[] value) {
        List<byte[]> splitValue = DataType.split(value);
        return tuple(t1.unmarshal(splitValue.getFirst()), t2.unmarshal(splitValue.get(1)), t3.unmarshal(splitValue.get(2)));
      }

      @Override
      public byte[] marshal(Tuple3<T1, T2, T3> value) {
        return concat(t1.marshal(value.t1()), t2.marshal(value.t2()), t3.marshal(value.t3()));
      }
    };

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
