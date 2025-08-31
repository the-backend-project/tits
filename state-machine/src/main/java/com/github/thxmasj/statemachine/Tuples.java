package com.github.thxmasj.statemachine;

public class Tuples {

  public static class Type<T> {

    public final String name;

    public Type(String name) {this.name = name;}

  }

  public record Tuple2<T1, T2>(T1 t1, T2 t2) {}
  public record Tuple3<T1, T2, T3>(T1 t1, T2 t2, T3 t3) {}
  public record Tuple4<T1, T2, T3, T4>(T1 t1, T2 t2, T3 t3, T4 t4) {}

  public static <T1, T2> Tuple2<T1, T2> tuple(T1 t1, T2 t2) {
    return new Tuple2<>(t1, t2);
  }

  public static <T, T1, T2> Type<T> type(Class<T1> t1, Class<T2> t2) {
    return new Type<>("(" + t1.getSimpleName() + ", " + t2.getSimpleName() + ")");
  }

  public static <T1, T2, T3> Tuple3<T1, T2, T3> tuple(T1 t1, T2 t2, T3 t3) {
    return new Tuple3<>(t1, t2, t3);
  }

  public static <T1, T2, T3, T4> Tuple4<T1, T2, T3, T4> tuple(T1 t1, T2 t2, T3 t3, T4 t4) {
    return new Tuple4<>(t1, t2, t3, t4);
  }

}
