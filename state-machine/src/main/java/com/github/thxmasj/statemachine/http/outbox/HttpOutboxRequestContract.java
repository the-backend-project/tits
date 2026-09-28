package com.github.thxmasj.statemachine.http.outbox;

import com.github.thxmasj.statemachine.DataType;
import com.github.thxmasj.statemachine.TransitionModelBuilder.TransitionContext;
import com.github.thxmasj.statemachine.Validated;
import com.github.thxmasj.statemachine.message.http.HttpResponseMessage;
import com.github.thxmasj.statemachine.message.http.TypedHttpRequest;
import com.github.thxmasj.statemachine.message.http.TypedHttpResponse;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import reactor.core.publisher.Mono;

public record HttpOutboxRequestContract<I, RQ, RS, S>(
    Function<TransitionContext<I>, Mono<TypedHttpRequest<RQ>>> messageCreator,
    DataType<RQ> requestPayloadType,
    DataType<RS> responsePayloadType,
    BiFunction<TypedHttpRequest<RQ>, HttpResponseMessage, Validated<RS>> contentParser,
    Predicate<TypedHttpResponse<RS>> isAccepted,
    Predicate<TypedHttpResponse<RS>> isRejected,
    Function<TypedHttpResponse<RS>, S> responseAdapter,
    Function<TypedHttpResponse<RS>, ?> failureAdapter,
    BiFunction<TransitionContext<Void>, TypedHttpRequest<RQ>, TypedHttpRequest<RQ>> repeatMessageCreator
) {

  public HttpOutboxRequestContract(
      Function<TransitionContext<I>, Mono<TypedHttpRequest<RQ>>> messageCreator,
      DataType<RQ> requestPayloadType,
      DataType<RS> responsePayloadType,
      BiFunction<TypedHttpRequest<RQ>, HttpResponseMessage, Validated<RS>> contentParser,
      Predicate<TypedHttpResponse<RS>> isAccepted,
      Predicate<TypedHttpResponse<RS>> isRejected,
      Function<TypedHttpResponse<RS>, S> responseAdapter,
      Function<TypedHttpResponse<RS>, ?> failureAdapter
  ) {
    this(messageCreator, requestPayloadType, responsePayloadType, contentParser, isAccepted, isRejected, responseAdapter, failureAdapter, null);
  }

  public HttpOutboxRequestContract(
      Function<TransitionContext<I>, Mono<TypedHttpRequest<RQ>>> messageCreator,
      DataType<RQ> requestPayloadType,
      DataType<RS> responsePayloadType,
      BiFunction<TypedHttpRequest<RQ>, HttpResponseMessage, Validated<RS>> contentParser,
      Predicate<TypedHttpResponse<RS>> isAccepted,
      Predicate<TypedHttpResponse<RS>> isRejected,
      Function<TypedHttpResponse<RS>, S> responseAdapter
  ) {
    this(messageCreator, requestPayloadType, responsePayloadType, contentParser, isAccepted, isRejected, responseAdapter, null, null);
  }

  public Function<TypedHttpResponse<RS>, S> acquirerResponseAdapter() {
    return responseAdapter;
  }

  public Function<TypedHttpResponse<RS>, ?> failedResponseAdapter() {
    return failureAdapter;
  }

  public static <I, RQ, RS, S> Builder<I, RQ, RS, S> builder() {
    return new Builder<>();
  }

  public static class Builder<I, RQ, RS, S> {
    private Function<TransitionContext<I>, Mono<TypedHttpRequest<RQ>>> messageCreator;
    private DataType<RQ> requestPayloadType;
    private DataType<RS> responsePayloadType;
    private BiFunction<TypedHttpRequest<RQ>, HttpResponseMessage, Validated<RS>> contentParser;
    private Predicate<TypedHttpResponse<RS>> isAccepted = _ -> true;
    private Predicate<TypedHttpResponse<RS>> isRejected = _ -> false;
    private Function<TypedHttpResponse<RS>, S> responseAdapter;
    private Function<TypedHttpResponse<RS>, ?> failureAdapter;
    private BiFunction<TransitionContext<Void>, TypedHttpRequest<RQ>, TypedHttpRequest<RQ>> repeatMessageCreator;

    public Builder<I, RQ, RS, S> messageCreator(Function<TransitionContext<I>, TypedHttpRequest<RQ>> messageCreator) {
      this.messageCreator = c -> Mono.just(messageCreator.apply(c));
      return this;
    }

    public Builder<I, RQ, RS, S> messageCreatorReactive(Function<TransitionContext<I>, Mono<TypedHttpRequest<RQ>>> messageCreator) {
      this.messageCreator = messageCreator;
      return this;
    }

    public Builder<I, RQ, RS, S> requestPayloadType(DataType<RQ> requestPayloadType) {
      this.requestPayloadType = requestPayloadType;
      return this;
    }

    public Builder<I, RQ, RS, S> responsePayloadType(DataType<RS> responsePayloadType) {
      this.responsePayloadType = responsePayloadType;
      return this;
    }

    public Builder<I, RQ, RS, S> contentParser(Function<HttpResponseMessage, Validated<RS>> contentParser) {
      this.contentParser = (_, response) -> contentParser.apply(response);
      return this;
    }

    public Builder<I, RQ, RS, S> contentParser(BiFunction<TypedHttpRequest<RQ>, HttpResponseMessage, Validated<RS>> contentParser) {
      this.contentParser = contentParser;
      return this;
    }

    public Builder<I, RQ, RS, S> isAccepted(Predicate<TypedHttpResponse<RS>> isAccepted) {
      this.isAccepted = isAccepted;
      return this;
    }

    public Builder<I, RQ, RS, S> isRejected(Predicate<TypedHttpResponse<RS>> isRejected) {
      this.isRejected = isRejected;
      return this;
    }

    public Builder<I, RQ, RS, S> responseAdapter(Function<TypedHttpResponse<RS>, S> responseAdapter) {
      this.responseAdapter = responseAdapter;
      return this;
    }

    public Builder<I, RQ, RS, S> acquirerResponseAdapter(Function<TypedHttpResponse<RS>, S> responseAdapter) {
      this.responseAdapter = responseAdapter;
      return this;
    }

    public Builder<I, RQ, RS, S> failureAdapter(Function<TypedHttpResponse<RS>, ?> failureAdapter) {
      this.failureAdapter = failureAdapter;
      return this;
    }

    public Builder<I, RQ, RS, S> failedResponseAdapter(Function<TypedHttpResponse<RS>, ?> failureAdapter) {
      this.failureAdapter = failureAdapter;
      return this;
    }

    public Builder<I, RQ, RS, S> repeatMessageCreator(
        BiFunction<TransitionContext<Void>, TypedHttpRequest<RQ>, TypedHttpRequest<RQ>> repeatMessageCreator
    ) {
      this.repeatMessageCreator = repeatMessageCreator;
      return this;
    }

    public Builder<I, RQ, RS, S> repeatMessageCreator(
        Function<TypedHttpRequest<RQ>, TypedHttpRequest<RQ>> repeatMessageCreator
    ) {
      this.repeatMessageCreator = (_, message) -> repeatMessageCreator.apply(message);
      return this;
    }

    public HttpOutboxRequestContract<I, RQ, RS, S> build() {
      return new HttpOutboxRequestContract<>(
          messageCreator,
          requestPayloadType,
          responsePayloadType,
          contentParser,
          isAccepted,
          isRejected,
          responseAdapter,
          failureAdapter,
          repeatMessageCreator
      );
    }
  }

  public static <I, RQ, RS, S> HttpOutboxRequestContract<I, RQ, RS, S> of(
      Function<TransitionContext<I>, TypedHttpRequest<RQ>> messageCreator,
      DataType<RQ> requestPayloadType,
      DataType<RS> responsePayloadType,
      Function<HttpResponseMessage, Validated<RS>> contentParser,
      Predicate<TypedHttpResponse<RS>> isAccepted,
      Predicate<TypedHttpResponse<RS>> isRejected,
      Function<TypedHttpResponse<RS>, S> responseAdapter,
      Function<TypedHttpResponse<RS>, ?> failureAdapter
  ) {
    return new HttpOutboxRequestContract<>(
        c -> Mono.just(messageCreator.apply(c)),
        requestPayloadType,
        responsePayloadType,
        (_, response) -> contentParser.apply(response),
        isAccepted,
        isRejected,
        responseAdapter,
        failureAdapter
    );
  }

  public static <I, RQ, RS, S> HttpOutboxRequestContract<I, RQ, RS, S> of(
      Function<TransitionContext<I>, TypedHttpRequest<RQ>> messageCreator,
      DataType<RQ> requestPayloadType,
      DataType<RS> responsePayloadType,
      Function<HttpResponseMessage, Validated<RS>> contentParser,
      Predicate<TypedHttpResponse<RS>> isAccepted,
      Predicate<TypedHttpResponse<RS>> isRejected,
      Function<TypedHttpResponse<RS>, S> responseAdapter
  ) {
    return of(messageCreator, requestPayloadType, responsePayloadType, contentParser, isAccepted, isRejected, responseAdapter, null);
  }

  public static <I, RQ, RS, S> HttpOutboxRequestContract<I, RQ, RS, S> of(
      Function<TransitionContext<I>, TypedHttpRequest<RQ>> messageCreator,
      DataType<RQ> requestPayloadType,
      DataType<RS> responsePayloadType,
      BiFunction<TypedHttpRequest<RQ>, HttpResponseMessage, Validated<RS>> contentParser,
      Predicate<TypedHttpResponse<RS>> isAccepted,
      Predicate<TypedHttpResponse<RS>> isRejected,
      Function<TypedHttpResponse<RS>, S> responseAdapter,
      Function<TypedHttpResponse<RS>, ?> failureAdapter
  ) {
    return new HttpOutboxRequestContract<>(
        c -> Mono.just(messageCreator.apply(c)),
        requestPayloadType,
        responsePayloadType,
        contentParser,
        isAccepted,
        isRejected,
        responseAdapter,
        failureAdapter
    );
  }

  public static <I, RQ, RS, S> HttpOutboxRequestContract<I, RQ, RS, S> of(
      Function<TransitionContext<I>, TypedHttpRequest<RQ>> messageCreator,
      DataType<RQ> requestPayloadType,
      DataType<RS> responsePayloadType,
      BiFunction<TypedHttpRequest<RQ>, HttpResponseMessage, Validated<RS>> contentParser,
      Predicate<TypedHttpResponse<RS>> isAccepted,
      Predicate<TypedHttpResponse<RS>> isRejected,
      Function<TypedHttpResponse<RS>, S> responseAdapter
  ) {
    return of(messageCreator, requestPayloadType, responsePayloadType, contentParser, isAccepted, isRejected, responseAdapter, null);
  }

  public static <I, RQ, RS> HttpOutboxRequestContract<I, RQ, RS, Void> of(
      Function<TransitionContext<I>, TypedHttpRequest<RQ>> messageCreator,
      DataType<RQ> requestPayloadType,
      DataType<RS> responsePayloadType,
      Function<HttpResponseMessage, Validated<RS>> contentParser,
      Predicate<TypedHttpResponse<RS>> isAccepted,
      Predicate<TypedHttpResponse<RS>> isRejected
  ) {
    return of(messageCreator, requestPayloadType, responsePayloadType, contentParser, isAccepted, isRejected, null, null);
  }

  public static <I, RQ, RS> HttpOutboxRequestContract<I, RQ, RS, Void> of(
      Function<TransitionContext<I>, TypedHttpRequest<RQ>> messageCreator,
      DataType<RQ> requestPayloadType,
      DataType<RS> responsePayloadType,
      BiFunction<TypedHttpRequest<RQ>, HttpResponseMessage, Validated<RS>> contentParser,
      Predicate<TypedHttpResponse<RS>> isAccepted,
      Predicate<TypedHttpResponse<RS>> isRejected
  ) {
    return of(messageCreator, requestPayloadType, responsePayloadType, contentParser, isAccepted, isRejected, null, null);
  }

  public static <I, RQ, RS> HttpOutboxRequestContract<I, RQ, RS, Void> of(
      Function<TransitionContext<I>, TypedHttpRequest<RQ>> messageCreator,
      DataType<RQ> requestPayloadType,
      DataType<RS> responsePayloadType,
      Function<HttpResponseMessage, Validated<RS>> contentParser
  ) {
    return of(messageCreator, requestPayloadType, responsePayloadType, contentParser, _ -> true, _ -> false, null, null);
  }

  public static <I, RQ, RS> HttpOutboxRequestContract<I, RQ, RS, Void> of(
      Function<TransitionContext<I>, TypedHttpRequest<RQ>> messageCreator,
      DataType<RQ> requestPayloadType,
      DataType<RS> responsePayloadType,
      BiFunction<TypedHttpRequest<RQ>, HttpResponseMessage, Validated<RS>> contentParser
  ) {
    return of(messageCreator, requestPayloadType, responsePayloadType, contentParser, _ -> true, _ -> false, null, null);
  }

  public static <I, RQ> HttpOutboxRequestContract<I, RQ, byte[], Void> of(
      Function<TransitionContext<I>, TypedHttpRequest<RQ>> messageCreator,
      DataType<RQ> requestPayloadType
  ) {
    return new HttpOutboxRequestContract<>(
        c -> Mono.just(messageCreator.apply(c)),
        requestPayloadType,
        DataType.binary(),
        (_, response) -> AnyResponseValidator.any().apply(response),
        _ -> true,
        _ -> false,
        null,
        null
    );
  }

  public static <I, RQ, RS, S> HttpOutboxRequestContract<I, RQ, RS, S> ofReactive(
      Function<TransitionContext<I>, Mono<TypedHttpRequest<RQ>>> messageCreator,
      DataType<RQ> requestPayloadType,
      DataType<RS> responsePayloadType,
      Function<HttpResponseMessage, Validated<RS>> contentParser,
      Predicate<TypedHttpResponse<RS>> isAccepted,
      Predicate<TypedHttpResponse<RS>> isRejected,
      Function<TypedHttpResponse<RS>, S> responseAdapter,
      Function<TypedHttpResponse<RS>, ?> failureAdapter
  ) {
    return new HttpOutboxRequestContract<>(
        messageCreator,
        requestPayloadType,
        responsePayloadType,
        (_, response) -> contentParser.apply(response),
        isAccepted,
        isRejected,
        responseAdapter,
        failureAdapter
    );
  }

  public static <I, RQ, RS, S> HttpOutboxRequestContract<I, RQ, RS, S> ofReactive(
      Function<TransitionContext<I>, Mono<TypedHttpRequest<RQ>>> messageCreator,
      DataType<RQ> requestPayloadType,
      DataType<RS> responsePayloadType,
      Function<HttpResponseMessage, Validated<RS>> contentParser,
      Predicate<TypedHttpResponse<RS>> isAccepted,
      Predicate<TypedHttpResponse<RS>> isRejected,
      Function<TypedHttpResponse<RS>, S> responseAdapter
  ) {
    return ofReactive(messageCreator, requestPayloadType, responsePayloadType, contentParser, isAccepted, isRejected, responseAdapter, null);
  }

  public static <I, RQ, RS, S> HttpOutboxRequestContract<I, RQ, RS, S> ofReactive(
      Function<TransitionContext<I>, Mono<TypedHttpRequest<RQ>>> messageCreator,
      DataType<RQ> requestPayloadType,
      DataType<RS> responsePayloadType,
      BiFunction<TypedHttpRequest<RQ>, HttpResponseMessage, Validated<RS>> contentParser,
      Predicate<TypedHttpResponse<RS>> isAccepted,
      Predicate<TypedHttpResponse<RS>> isRejected,
      Function<TypedHttpResponse<RS>, S> responseAdapter,
      Function<TypedHttpResponse<RS>, ?> failureAdapter
  ) {
    return new HttpOutboxRequestContract<>(
        messageCreator,
        requestPayloadType,
        responsePayloadType,
        contentParser,
        isAccepted,
        isRejected,
        responseAdapter,
        failureAdapter
    );
  }

  public static <I, RQ, RS, S> HttpOutboxRequestContract<I, RQ, RS, S> ofReactive(
      Function<TransitionContext<I>, Mono<TypedHttpRequest<RQ>>> messageCreator,
      DataType<RQ> requestPayloadType,
      DataType<RS> responsePayloadType,
      BiFunction<TypedHttpRequest<RQ>, HttpResponseMessage, Validated<RS>> contentParser,
      Predicate<TypedHttpResponse<RS>> isAccepted,
      Predicate<TypedHttpResponse<RS>> isRejected,
      Function<TypedHttpResponse<RS>, S> responseAdapter
  ) {
    return ofReactive(messageCreator, requestPayloadType, responsePayloadType, contentParser, isAccepted, isRejected, responseAdapter, null);
  }

  public static <I, RQ, RS> HttpOutboxRequestContract<I, RQ, RS, Void> ofReactive(
      Function<TransitionContext<I>, Mono<TypedHttpRequest<RQ>>> messageCreator,
      DataType<RQ> requestPayloadType,
      DataType<RS> responsePayloadType,
      Function<HttpResponseMessage, Validated<RS>> contentParser,
      Predicate<TypedHttpResponse<RS>> isAccepted,
      Predicate<TypedHttpResponse<RS>> isRejected
  ) {
    return ofReactive(messageCreator, requestPayloadType, responsePayloadType, contentParser, isAccepted, isRejected, null, null);
  }

  public static <I, RQ, RS> HttpOutboxRequestContract<I, RQ, RS, Void> ofReactive(
      Function<TransitionContext<I>, Mono<TypedHttpRequest<RQ>>> messageCreator,
      DataType<RQ> requestPayloadType,
      DataType<RS> responsePayloadType,
      BiFunction<TypedHttpRequest<RQ>, HttpResponseMessage, Validated<RS>> contentParser,
      Predicate<TypedHttpResponse<RS>> isAccepted,
      Predicate<TypedHttpResponse<RS>> isRejected
  ) {
    return ofReactive(messageCreator, requestPayloadType, responsePayloadType, contentParser, isAccepted, isRejected, null, null);
  }

  public static <I, RQ, RS> HttpOutboxRequestContract<I, RQ, RS, Void> ofReactive(
      Function<TransitionContext<I>, Mono<TypedHttpRequest<RQ>>> messageCreator,
      DataType<RQ> requestPayloadType,
      DataType<RS> responsePayloadType,
      Function<HttpResponseMessage, Validated<RS>> contentParser
  ) {
    return ofReactive(messageCreator, requestPayloadType, responsePayloadType, contentParser, _ -> true, _ -> false, null, null);
  }

  public static <I, RQ, RS> HttpOutboxRequestContract<I, RQ, RS, Void> ofReactive(
      Function<TransitionContext<I>, Mono<TypedHttpRequest<RQ>>> messageCreator,
      DataType<RQ> requestPayloadType,
      DataType<RS> responsePayloadType,
      BiFunction<TypedHttpRequest<RQ>, HttpResponseMessage, Validated<RS>> contentParser
  ) {
    return ofReactive(messageCreator, requestPayloadType, responsePayloadType, contentParser, _ -> true, _ -> false, null, null);
  }

  public static <I, RQ> HttpOutboxRequestContract<I, RQ, byte[], Void> ofReactive(
      Function<TransitionContext<I>, Mono<TypedHttpRequest<RQ>>> messageCreator,
      DataType<RQ> requestPayloadType
  ) {
    return new HttpOutboxRequestContract<>(
        messageCreator,
        requestPayloadType,
        DataType.binary(),
        (_, response) -> AnyResponseValidator.any().apply(response),
        _ -> true,
        _ -> false,
        null,
        null
    );
  }
}
