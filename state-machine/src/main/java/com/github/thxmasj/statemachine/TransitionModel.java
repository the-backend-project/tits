package com.github.thxmasj.statemachine;

import static com.github.thxmasj.statemachine.BuiltinEventTypes.InvalidRequest;
import static java.util.Objects.requireNonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;
import reactor.core.publisher.Mono;

public class TransitionModel<I, O> {

  private final State fromState;
  private final State toState;
  private final EventType eventType;
  private final Class<? extends DataCreator<I, O>> dataCreatorType;
  private final DataCreator<I, O> dataCreator;
  private final List<Filter<O>> filters = new ArrayList<>();
  private final List<Function<O, EventTriggerBuilder>> eventTriggers = new ArrayList<>();
  private final List<OutgoingRequestModel<O, ?>> outgoingRequests = new ArrayList<>();
  private final List<OutgoingResponseModel<O, ?>> outgoingResponses = new ArrayList<>();
  private TransitionModel<Void, ?> reverse;
  private final List<Function<O, SecondaryId>> newIdentifiers = new ArrayList<>();
  private List<ScheduledEvent> scheduledEvents = List.of();

  private TransitionModel(State fromState, State toState, EventType eventType, DataCreator<I, O> dataCreator, Class<? extends DataCreator<I, O>> dataCreatorType) {
    this.fromState = requireNonNull(fromState);
    this.toState = requireNonNull(toState);
    this.eventType = requireNonNull(eventType);
    this.dataCreator = dataCreator;
    this.dataCreatorType = dataCreatorType;
  }

  private TransitionModel(State fromState, State toState, EventType eventType, DataCreator<I, O> dataCreator) {
    this(fromState, toState, eventType, dataCreator, null);
  }

  private TransitionModel(State fromState, State toState, EventType eventType, Class<? extends DataCreator<I, O>> dataCreatorType) {
    this(fromState, toState, eventType, null, dataCreatorType);
  }

  public static class FilterBuilder<I, O> {
    private final TransitionModel<I, O> owner;
    private final Predicate<O> filter;

    public FilterBuilder(TransitionModel<I, O> owner, Predicate<O> filter) {
      this.owner = owner;
      this.filter = filter;
    }

    public <T> TransitionModel<I, O> orElse(EventType eventType, T data) {
      owner.filters.add(new Filter<>(filter, _ -> new InputEvent<>(eventType, data, null)));
      return owner;
    }

    public <T> TransitionModel<I, O> orElseInvalidRequest(String errorMessage) {
      owner.filters.add(new Filter<>(filter, _ -> new InputEvent<>(InvalidRequest, null, errorMessage)));
      return owner;
    }

    public <T> TransitionModel<I, O> orElse(EventType eventType, Function<O, T> dataAdapter) {
      owner.filters.add(new Filter<>(filter, data -> new InputEvent<>(eventType, dataAdapter.apply(data), null)));
      return owner;
    }
  }

  public record Filter<O>(Predicate<O> filter, Function<O, InputEvent<?>> alternative) {}

  public FilterBuilder<I, O> filter(Predicate<O> filter) {
    return new FilterBuilder<>(this, filter);
  }

  public TransitionModel<I, O> notify(OutgoingRequestModel.Builder<O, ?> request) {
    this.outgoingRequests.add(request.build());
    return this;
  }

  public TransitionModel<I, O> trigger(Function<O, EventTriggerBuilder>builder) {
    this.eventTriggers.add(builder);
    return this;
  }

  public TransitionModel<I, O> reverse(Function<TransitionModel.Builder, TransitionModel<Void, ?>> builder) {
    this.reverse = builder.apply(Builder.from(toState).to(fromState).onEvent(eventType));
    return this;
  }

  public TransitionModel<I, O> guaranteedNotification(
      Class<? extends OutgoingRequestCreator<O>> notificationCreator,
      OutboxQueue queue
  ) {
    this.outgoingRequests.add(new OutgoingRequestModel<>(Function.identity(), notificationCreator, null, queue, true, 0, null, null));
    return this;
  }

  public <U> TransitionModel<I, O> response(Function<O, U> dataAdapter, OutgoingResponseCreator<U> responseCreator) {
    this.outgoingResponses.add(new OutgoingResponseModel<>(
        dataAdapter,
        null,
        responseCreator
    ));
    return this;
  }

  public <U> TransitionModel<I, O> response(Function<O, U> dataAdapter, Class<? extends OutgoingResponseCreator<U>> creatorType) {
    this.outgoingResponses.add(new OutgoingResponseModel<>(
        dataAdapter,
        creatorType,
        null
    ));
    return this;
  }

  public TransitionModel<I, O> response(OutgoingResponseCreator<O> creator) {
    this.outgoingResponses.add(new OutgoingResponseModel<>(
        Function.identity(),
        null,
        creator
    ));
    return this;
  }

  public TransitionModel<I, O> response(Class<? extends OutgoingResponseCreator<O>> creatorType) {
    this.outgoingResponses.add(new OutgoingResponseModel<>(
        Function.identity(),
        creatorType,
        null
    ));
    return this;
  }

  public TransitionModel<I, O> scheduledEvents(
      List<ScheduledEvent> scheduledEvents
  ) {
    this.scheduledEvents = scheduledEvents;
    return this;
  }

  public TransitionModel<I, O> newIdentifier(Function<O, SecondaryId> newIdentifier) {
    this.newIdentifiers.add(newIdentifier);
    return this;
  }

  public State fromState() {
    return fromState;
  }

  public State toState() {
    return toState;
  }

  public EventType eventType() {
    return eventType;
  }

  public List<Filter<O>> filters() {
    return filters;
  }

  public List<OutgoingRequestModel<O, ?>> outgoingRequests() {
    return outgoingRequests;
  }

  public List<OutgoingResponseModel<O, ?>> outgoingResponses() {
    return outgoingResponses;
  }

  public List<Function<O, EventTriggerBuilder>> eventTriggers() {
    return eventTriggers;
  }

  public TransitionModel<Void, ?> reverse() {
    return reverse;
  }

  public DataCreator<I, O> dataCreator() {
    return dataCreator;
  }

  public Class<? extends DataCreator<I, O>> dataCreatorType() {
    return dataCreatorType;
  }

  public List<ScheduledEvent> scheduledEvents() {
    return scheduledEvents;
  }

  public List<Function<O, SecondaryId>> newIdentifiers() {
    return newIdentifiers;
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

  public static class Builder {
    private State fromState;
    private State toState;
    private EventType eventType;

    public static Builder from(State fromState) {
      var b = new Builder();
      b.fromState = fromState;
      return b;
    }

    public Builder to(State toState) {
      this.toState = toState;
      return this;
    }

    public Builder toSelf() {
      this.toState = fromState;
      return this;
    }

    public Builder onEvent(EventType eventType) {
      this.eventType = eventType;
      return this;
    }

    public <I, O> TransitionModel<I, O> withData(DataCreator<I, O> dataCreator) {
      return new TransitionModel<>(fromState, toState, eventType, dataCreator);
    }

    public <I, O> TransitionModel<I, O> withData(Class<? extends DataCreator<I, O>> dataCreatorType) {
      return new TransitionModel<>(fromState, toState, eventType, dataCreatorType);
    }

    public TransitionModel<String, String> response(OutgoingResponseCreator<String> creator) {
      return new TransitionModel<String, String>(fromState, toState, eventType, null, null)
          .response(creator);
    }

    public TransitionModel<String, String> response(String data, OutgoingResponseCreator<String> creator) {
      return new TransitionModel<String, String>(fromState, toState, eventType, (_, _) -> Mono.just(data), null)
          .response(creator);
    }

    public TransitionModel<String, String> build() {
      return new TransitionModel<>(fromState, toState, eventType, null, null);
    }

  }

}
