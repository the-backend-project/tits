package com.github.thxmasj.statemachine;

public class Tuples {

  public record Tuple2<T, U>(T t1, U t2) {}

  public static <T, U> Tuple2<T, U> tuple(T t1, U t2) {
    return new Tuple2<>(t1, t2);
  }

  public record Tuple3<T, U, V>(T t1, U t2,  V t3) {}

  public static <T, U, V> Tuple3<T, U, V> tuple(T t1, U t2, V t3) {
    return new Tuple3<>(t1, t2, t3);
  }

}
