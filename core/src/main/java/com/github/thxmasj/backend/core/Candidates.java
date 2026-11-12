package com.github.thxmasj.backend.core;

import java.util.function.Function;
import java.util.function.Supplier;
import reactor.core.publisher.Mono;

/// A lazily calculated sequence of alternatives, in order of preference. Unlike with a Flux, the next alternative is
/// calculated only when explicitly asked for, never ahead of it, as Reactor's operators may request elements before
/// the previous ones are consumed. An alternative may thus depend on what was learned from consuming the previous ones.
public final class Candidates<T> {

  private record Candidate<T>(T value, Supplier<Candidates<T>> next) {}

  private final Mono<Candidate<T>> head;

  private Candidates(Mono<Candidate<T>> head) {
    this.head = head;
  }

  public static <T> Candidates<T> empty() {
    return new Candidates<>(Mono.empty());
  }

  public static <T> Candidates<T> just(T value) {
    return new Candidates<>(Mono.just(new Candidate<>(value, Candidates::empty)));
  }

  /// The value of the Mono as the only candidate, or no candidate if it is empty
  public static <T> Candidates<T> from(Mono<? extends T> mono) {
    return new Candidates<>(mono.map(value -> new Candidate<>(value, Candidates::empty)));
  }

  public <R> Candidates<R> map(Function<? super T, ? extends R> mapper) {
    return new Candidates<>(head.map(c -> new Candidate<>(mapper.apply(c.value()), () -> c.next().get().map(mapper))));
  }

  /// The candidates of each candidate, in order
  public <R> Candidates<R> concatMap(Function<? super T, ? extends Candidates<? extends R>> mapper) {
    return new Candidates<>(head.flatMap(c -> Candidates.<R>narrow(mapper.apply(c.value()))
        .concatWith(() -> c.next().get().concatMap(mapper))
        .head
    ));
  }

  /// These candidates, followed by the ones supplied when asked for the candidate after the last of these
  public Candidates<T> concatWith(Supplier<? extends Candidates<? extends T>> rest) {
    return new Candidates<>(head
        .map(c -> new Candidate<>(c.value(), () -> c.next().get().concatWith(rest)))
        .switchIfEmpty(Mono.defer(() -> Candidates.<T>narrow(rest.get()).head))
    );
  }

  /// Continues with the fallback candidates if calculating a candidate fails with the given error
  public <E extends Throwable> Candidates<T> onErrorResume(
      Class<E> type,
      Function<? super E, ? extends Candidates<? extends T>> fallback
  ) {
    return new Candidates<>(head
        .map(c -> new Candidate<>(c.value(), () -> c.next().get().onErrorResume(type, fallback)))
        .onErrorResume(type, e -> Candidates.<T>narrow(fallback.apply(e)).head)
    );
  }

  /// The first candidate. Empty if there is none.
  public Mono<T> first() {
    return head.map(Candidate::value);
  }

  /// The result of the first candidate for which the function gives one. Each candidate is calculated after the
  /// function has completed empty for the previous one. Empty if no candidate gives a result.
  public <R> Mono<R> firstResult(Function<? super T, ? extends Mono<? extends R>> f) {
    return head.flatMap(c -> Mono.<R>from(f.apply(c.value()))
        .switchIfEmpty(Mono.defer(() -> c.next().get().firstResult(f)))
    );
  }

  // Safe, as candidates are only ever read
  @SuppressWarnings("unchecked")
  private static <T> Candidates<T> narrow(Candidates<? extends T> candidates) {
    return (Candidates<T>) candidates;
  }

}
