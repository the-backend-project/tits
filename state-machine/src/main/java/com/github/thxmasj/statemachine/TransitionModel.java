package com.github.thxmasj.statemachine;

import static com.github.thxmasj.statemachine.BuiltinEventTypes.InvalidRequest;
import static com.github.thxmasj.statemachine.BuiltinEventTypes.Rollback;
import static java.util.Objects.requireNonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;
import reactor.core.publisher.Mono;

public class TransitionModel<I, P, O> {

  private final State fromState;
  private final State toState;
  private final EventType<I, O> eventType;
  private Function<P, O> outputTransformation = _ -> null;
  private final Class<? extends ReactiveDataCreator<I, P>> dataCreatorType;
  private final ReactiveDataCreator<I, P> dataCreator;
  private final List<Filter<P, ?>> filters = new ArrayList<>();
  private final List<Function<P, EventTriggerBuilder<?, ?>>> eventTriggers = new ArrayList<>();
  private final List<OutgoingRequestModel<P, ?>> outgoingRequests = new ArrayList<>();
  private final List<OutgoingResponseModel<P, ?>> outgoingResponses = new ArrayList<>();
  private TransitionModel<Void, ?, ?> reverse;
  private final List<Function<P, SecondaryId>> newIdentifiers = new ArrayList<>();
  private List<ScheduledEvent> scheduledEvents = List.of();

  private TransitionModel(State fromState, State toState, EventType<I, O> eventType, ReactiveDataCreator<I, P> dataCreator, Class<? extends ReactiveDataCreator<I, P>> dataCreatorType) {
    this.fromState = requireNonNull(fromState);
    this.toState = requireNonNull(toState);
    this.eventType = requireNonNull(eventType);
    this.dataCreator = dataCreator;
    this.dataCreatorType = dataCreatorType;
  }

  private TransitionModel(State fromState, State toState, EventType<I, O> eventType, ReactiveDataCreator<I, P> dataCreator) {
    this(fromState, toState, eventType, dataCreator, null);
  }

  private TransitionModel(State fromState, State toState, EventType<I, O> eventType, Class<? extends ReactiveDataCreator<I, P>> dataCreatorType) {
    this(fromState, toState, eventType, null, dataCreatorType);
  }

  public Function<P, O> outputTransformation() {
    return requireNonNull(outputTransformation, id() + ": Missing output transformation");
  }

  public static class FilterBuilder<I, P, O> {
    private final TransitionModel<I, P, O> owner;
    private final Predicate<P> filter;

    public FilterBuilder(TransitionModel<I, P, O> owner, Predicate<P> filter) {
      this.owner = owner;
      this.filter = filter;
    }

    public TransitionModel<I, P, O> orElseInvalidRequest(String errorMessage) {
      owner.filters.add(new Filter<>(filter, _ -> new InputEvent<>(InvalidRequest, errorMessage)));
      return owner;
    }

    public <T> TransitionModel<I, P, O> orElse(EventType<T, ?> eventType, Function<P, T> dataAdapter) {
      owner.filters.add(new Filter<>(filter, data -> new InputEvent<>(eventType, dataAdapter.apply(data))));
      return owner;
    }
  }

  public record Filter<P, I2>(Predicate<P> filter, Function<P, InputEvent<I2>> alternative) {}

  public FilterBuilder<I, P, O> filter(Predicate<P> filter) {
    return new FilterBuilder<>(this, filter);
  }

  public TransitionModel<I, P, O> notify(OutgoingRequestModel.Builder<P, ?> request) {
    this.outgoingRequests.add(request.build());
    return this;
  }

  public TransitionModel<I, P, O> output(Function<P, O> outputTransformation) {
    this.outputTransformation = requireNonNull(outputTransformation);
    return this;
  }

  public TransitionModel<I, P, O> trigger(Function<P, EventTriggerBuilder<?, ?>> builder) {
    this.eventTriggers.add(builder);
    return this;
  }

  public TransitionModel<I, P, O> reverse(Function<TransitionModel.Builder<Void, ?>, TransitionModel<Void, ?, ?>> builder) {
    this.reverse = builder.apply(Builder.onEvent(Rollback).from(toState).to(fromState));
    return this;
  }

  public <U> TransitionModel<I, P, O> response(Function<P, U> dataAdapter, OutgoingResponseCreator<U> responseCreator) {
    this.outgoingResponses.add(new OutgoingResponseModel<>(
        dataAdapter,
        null,
        responseCreator
    ));
    return this;
  }

  public TransitionModel<I, P, O> response(String data, OutgoingResponseCreator<String> responseCreator) {
    this.outgoingResponses.add(new OutgoingResponseModel<>(
        _ -> data,
        null,
        responseCreator
    ));
    return this;
  }

  public <U> TransitionModel<I, P, O> response(Function<P, U> dataAdapter, Class<? extends OutgoingResponseCreator<U>> creatorType) {
    this.outgoingResponses.add(new OutgoingResponseModel<>(
        dataAdapter,
        creatorType,
        null
    ));
    return this;
  }

  public TransitionModel<I, P, O> response(OutgoingResponseCreator<P> creator) {
    this.outgoingResponses.add(new OutgoingResponseModel<>(
        Function.identity(),
        null,
        creator
    ));
    return this;
  }

  public TransitionModel<I, P, O> response(Class<? extends OutgoingResponseCreator<P>> creatorType) {
    this.outgoingResponses.add(new OutgoingResponseModel<>(
        Function.identity(),
        creatorType,
        null
    ));
    return this;
  }

  public TransitionModel<I, P, O> scheduledEvents(
      List<ScheduledEvent> scheduledEvents
  ) {
    this.scheduledEvents = scheduledEvents;
    return this;
  }

  public TransitionModel<I, P, O> newIdentifier(Function<P, SecondaryId> newIdentifier) {
    this.newIdentifiers.add(newIdentifier);
    return this;
  }

  public State fromState() {
    return fromState;
  }

  public State toState() {
    return toState;
  }

  public EventType<I, ?> eventType() {
    return eventType;
  }

  public List<Filter<P, ?>> filters() {
    return filters;
  }

  public List<OutgoingRequestModel<P, ?>> outgoingRequests() {
    return outgoingRequests;
  }

  public List<OutgoingResponseModel<P, ?>> outgoingResponses() {
    return outgoingResponses;
  }

  public List<Function<P, EventTriggerBuilder<?, ?>>> eventTriggers() {
    return eventTriggers;
  }

  public TransitionModel<Void, ?, ?> reverse() {
    return reverse;
  }

  public ReactiveDataCreator<I, P> dataCreator() {
    return dataCreator;
  }

  public Class<? extends ReactiveDataCreator<I, P>> dataCreatorType() {
    return dataCreatorType;
  }

  public List<ScheduledEvent> scheduledEvents() {
    return scheduledEvents;
  }

  public List<Function<P, SecondaryId>> newIdentifiers() {
    return newIdentifiers;
  }

  private String id() {
    return String.format("[%s]--(%s)-->[%s]", fromState.name(), eventType.name(), toState.name());
  }

  @Override
  public String toString() {
    return "TransitionModel{" +
        "fromState=" + fromState +
        ", toState=" + toState +
        ", eventType=" + eventType +
        ", filters=" + filters +
        ", dataCreatorType=" + dataCreatorType +
        ", dataCreator=" + dataCreator +
        ", eventTriggers=" + eventTriggers +
        ", outgoingRequests=" + outgoingRequests +
        ", outgoingResponses=" + outgoingResponses +
        ", reverse=" + reverse +
        ", newIdentifiers=" + newIdentifiers +
        ", scheduledEvents=" + scheduledEvents +
        '}';
  }

  public static class Builder<I, O> {
    private State fromState;
    private State toState;
    private EventType<I, O> eventType;

    public Builder<I, O> from(State fromState) {
      this.fromState = fromState;
      return this;
    }

    public Builder<I, O> to(State toState) {
      this.toState = toState;
      return this;
    }

    public Builder<I, O> toSelf() {
      this.toState = fromState;
      return this;
    }

    public static <I, O> Builder<I, O> onEvent(EventType<I, O> eventType) {
      var b = new Builder<I, O>();
      b.eventType = eventType;
      return b;
    }

    public <P> TransitionModel<I, P, O> withData(ReactiveDataCreator<I, P> dataCreator) {
      return new TransitionModel<>(fromState, toState, eventType, dataCreator);
    }

    public <P> TransitionModel<I, P, O> assemble(DataCreator<I, P> dataCreator) {
      return new TransitionModel<>(
          fromState, toState, eventType,
          (inputEvent, eventLog) -> Mono.just(dataCreator.execute(inputEvent, eventLog))
      );
    }

    public <P> TransitionModel<I, P, O> withData(Class<? extends ReactiveDataCreator<I, P>> dataCreatorType) {
      return new TransitionModel<>(fromState, toState, eventType, dataCreatorType);
    }

    public <P> TransitionModel<I, P, O> response(OutgoingResponseCreator<P> creator) {
      return new TransitionModel<>(fromState, toState, eventType, (ReactiveDataCreator<I, P>)null, null)
          .response(creator);
    }

    public <P> TransitionModel<I, P, O> response(P data, OutgoingResponseCreator<P> creator) {
      return new TransitionModel<>(fromState, toState, eventType, (_, _) -> Mono.just(data), null)
          .response(creator);
    }

    public <P> TransitionModel<I, P, O> build() {
      return new TransitionModel<>(fromState, toState, eventType, null, null);
    }

  }

}
