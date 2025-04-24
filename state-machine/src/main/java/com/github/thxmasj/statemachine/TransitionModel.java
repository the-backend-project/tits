package com.github.thxmasj.statemachine;

import reactor.core.publisher.Mono;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static java.util.Objects.requireNonNull;

public class TransitionModel<T> {

  private final State fromState;
  private final State toState;
  private final EventType eventType;
  private final Class<? extends DataCreator<T>> dataCreatorType;
  private final DataCreator<T> dataCreator;
  private final List<Function<T, EventTriggerBuilder>> eventTriggers = new ArrayList<>();
  private final List<OutgoingRequestModel<T, ?>> outgoingRequests = new ArrayList<>();
  private final List<OutgoingResponseModel<T, ?>> outgoingResponses = new ArrayList<>();
  private TransitionModel<?> reverse;
  private final List<Function<T, SecondaryId>> newIdentifiers = new ArrayList<>();
  private List<ScheduledEvent> scheduledEvents = List.of();

  private TransitionModel(State fromState, State toState, EventType eventType, DataCreator<T> dataCreator, Class<? extends DataCreator<T>> dataCreatorType) {
    this.fromState = requireNonNull(fromState);
    this.toState = requireNonNull(toState);
    this.eventType = requireNonNull(eventType);
    this.dataCreator = dataCreator;
    this.dataCreatorType = dataCreatorType;
  }

  private TransitionModel(State fromState, State toState, EventType eventType, DataCreator<T> dataCreator) {
    this(fromState, toState, eventType, dataCreator, null);
  }

  private TransitionModel(State fromState, State toState, EventType eventType, Class<? extends DataCreator<T>> dataCreatorType) {
    this(fromState, toState, eventType, null, dataCreatorType);
  }

  public TransitionModel<T> notify(OutgoingRequestModel.Builder<T, ?> request) {
    this.outgoingRequests.add(request.build());
    return this;
  }

  public TransitionModel<T> trigger(Function<T, EventTriggerBuilder>builder) {
    this.eventTriggers.add(builder);
    return this;
  }

  public TransitionModel<T> reverse(Function<TransitionModel.Builder, TransitionModel<?>> builder) {
    this.reverse = builder.apply(Builder.from(toState).to(fromState).onEvent(eventType));
    return this;
  }

  public TransitionModel<T> guaranteedNotification(
      Class<? extends OutgoingRequestCreator<T>> notificationCreator,
      Subscriber subscriber
  ) {
    this.outgoingRequests.add(new OutgoingRequestModel<>(Function.identity(), notificationCreator, null, subscriber, true, null));
    return this;
  }

  public TransitionModel<T> guaranteedNotification(
      Class<? extends OutgoingRequestCreator<T>> notificationCreator,
      Subscriber subscriber,
      IncomingResponseValidator<?> responseValidator
  ) {
    this.outgoingRequests.add(new OutgoingRequestModel<>(Function.identity(), notificationCreator, null, subscriber, true, responseValidator));
    return this;
  }

  public <U> TransitionModel<T> response(Function<T, U> dataAdapter, OutgoingResponseCreator<U> responseCreator) {
    this.outgoingResponses.add(new OutgoingResponseModel<>(
        dataAdapter,
        null,
        responseCreator
    ));
    return this;
  }

  public TransitionModel<T> response(OutgoingResponseCreator<T> creator) {
    this.outgoingResponses.add(new OutgoingResponseModel<>(
        Function.identity(),
        null,
        creator
    ));
    return this;
  }

  public TransitionModel<T> response(Class<? extends OutgoingResponseCreator<T>> creatorType) {
    this.outgoingResponses.add(new OutgoingResponseModel<>(
        Function.identity(),
        creatorType,
        null
    ));
    return this;
  }

  public TransitionModel<T> scheduledEvents(
      List<ScheduledEvent> scheduledEvents
  ) {
    this.scheduledEvents = scheduledEvents;
    return this;
  }

  public TransitionModel<T> newIdentifier(Function<T, SecondaryId> newIdentifier) {
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

  public List<OutgoingRequestModel<T, ?>> outgoingRequests() {
    return outgoingRequests;
  }

  public List<OutgoingResponseModel<T, ?>> outgoingResponses() {
    return outgoingResponses;
  }

  public List<Function<T, EventTriggerBuilder>> eventTriggers() {
    return eventTriggers;
  }

  public TransitionModel<?> reverse() {
    return reverse != null ?
        reverse :
        new TransitionModel<>(
            toState,
            fromState,
            eventType,
            null,
            null
        );
  }

  public DataCreator<T> dataCreator() {
    return dataCreator;
  }

  public Class<? extends DataCreator<T>> dataCreatorType() {
    return dataCreatorType;
  }

  public List<ScheduledEvent> scheduledEvents() {
    return scheduledEvents;
  }

  public List<Function<T, SecondaryId>> newIdentifiers() {
    return newIdentifiers;
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

    public Builder onEvent(EventType eventType) {
      this.eventType = eventType;
      return this;
    }

    public <T> TransitionModel<T> withData(DataCreator<T> dataCreator) {
      return new TransitionModel<>(fromState, toState, eventType, dataCreator);
    }

    public <T> TransitionModel<T> withData(Class<? extends DataCreator<T>> dataCreatorType) {
      return new TransitionModel<>(fromState, toState, eventType, dataCreatorType);
    }

    public TransitionModel<String> response(OutgoingResponseCreator<String> creator) {
      return new TransitionModel<>(fromState, toState, eventType, _ -> Mono.just(""), null)
          .response(creator);
    }

    public TransitionModel<String> build() {
      return new TransitionModel<>(fromState, toState, eventType, null, null);
    }

  }

}
