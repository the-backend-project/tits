package com.github.thxmasj.statemachine.http.outbox;

import static com.github.thxmasj.statemachine.IndexEntityModel.referenceEventType;
import static com.github.thxmasj.statemachine.database.mssql.Mappers.eventTypesFor;

import com.github.thxmasj.statemachine.BasicEventType;
import com.github.thxmasj.statemachine.DataType;
import com.github.thxmasj.statemachine.EventLog;
import com.github.thxmasj.statemachine.EventType;
import com.github.thxmasj.statemachine.IndexEntityModel;
import com.github.thxmasj.statemachine.State;
import com.github.thxmasj.statemachine.TransitionModelBuilder;
import com.github.thxmasj.statemachine.TransitionModelBuilder.TransitionModel;
import com.github.thxmasj.statemachine.http.HttpDataType;
import com.github.thxmasj.statemachine.message.http.HttpRequestMessage;
import com.github.thxmasj.statemachine.message.http.TypedHttpRequest;
import com.github.thxmasj.statemachine.message.http.TypedHttpResponse;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/// An outbox request, which dispatches request messages with payloads of type `RQ`. The request messages are created
/// outside of it, by the dispatcher of the request, so that the outbox request is independent of where it is used. See
/// [Dispatcher] for a way to pair an outbox request with the creator of its request messages.
public sealed interface HttpOutboxRequest<RQ> extends com.github.thxmasj.statemachine.EntityModel permits AtLeastOnce, AtMostOnce {

  Map<State, List<TransitionModelBuilder.TransitionModel<?, ?>>> transitions();

  default List<EventType<?, ?>> eventTypes() {
    return transitions().values()
        .stream()
        .flatMap(tl -> tl.stream())
        .flatMap(t -> eventTypesFor(t).stream())
        .distinct()
        .toList();
  }

  EventType<Input<RQ>, ?> requestDispatched();

  DataType<RQ> requestPayloadType();

  DataType<?> responsePayloadType();

  TypedHttpRequest<RQ> request(EventLog log);

  TypedHttpResponse<?> response(EventLog log);

  static AtLeastOnceBuilder.RequestPayloadTypeStep atLeastOnce() {
    return AtLeastOnceBuilder.create();
  }

  static AtMostOnceBuilder.RequestPayloadTypeStep atMostOnce() {
    return AtMostOnceBuilder.create();
  }

  /// Id of the request accepted event type, shared by all kinds of outbox requests so that a condition can refer to it
  /// without knowing which kind of request it is.
  UUID RequestAcceptedId = UUID.fromString("ee8e5913-a047-492c-ab16-55458ecb2930");

  /// The request accepted event type of any outbox request, agnostic to its input and output data types. Use it where
  /// only the identity of the event type matters, e.g. when stating the last event that satisfies a condition.
  static EventType<?, ?> requestAccepted() {
    return BasicEventType.of("[request accepted]", RequestAcceptedId, DataType.unknown(), DataType.unknown());
  }

  /// Id of the rollback dispatched event type, shared by all outbox requests that can be rolled back so that a
  /// condition can refer to it without knowing which kind of request it is.
  UUID RollbackDispatchedId = UUID.fromString("2cda741e-4447-4787-a5eb-46f0502e08ec");

  /// The rollback dispatched event type of any outbox request, agnostic to its input data type. Use it where only the
  /// identity of the event type matters, e.g. when stating the last event that satisfies a condition.
  static EventType<?, ?> rollbackDispatched() {
    return BasicEventType.of("Rollback dispatched", RollbackDispatchedId, DataType.unknown(), DataType.none());
  }

  EventType<UUID, UUID> ProcessReferenced = referenceEventType("Process referenced", UUID.fromString("d5b264d9-bf81-4c1d-9d9b-93dd556d0f66"));
  EventType<UUID, UUID> BatchReferenced = referenceEventType("Batch referenced", UUID.fromString("18055445-263e-4c95-9610-1aa780f205d7"));

  IndexEntityModel Index = IndexEntityModel.of(
      "Index",
      UUID.fromString("06e6e5e6-f893-44f8-8239-3bff5cca1338"),
      ProcessReferenced,
      BatchReferenced
  );

  static Map<State, List<TransitionModel<?, ?>>> combine(
      Map<State, List<TransitionModel<?, ?>>> m1,
      Map<State, List<TransitionModel<?, ?>>> m2
  ) {
    return Stream.concat(m1.entrySet().stream(), m2.entrySet().stream())
        .collect(Collectors.toMap(
            Map.Entry::getKey,
            Map.Entry::getValue,
            (a, b) -> {
              if (a.isEmpty() && b.isEmpty()) return a;
              throw new IllegalArgumentException("TransitionModel maps can't be combined - they use the same State key");
            }
        ));
  }

  static <T> HttpRequestMessage toHttpRequest(TypedHttpRequest<T> request, DataType<T> payloadType) {
    return new HttpRequestMessage(request.method(), request.uri(), request.headers(), payloadType.marshal(request.payload()));
  }

  /// The input of an outbox request: the request message to dispatch, and the id of the batch which the request
  /// belongs to. The batch id is needed by a batched outbox request only, and is null otherwise.
  record Input<RQ>(TypedHttpRequest<RQ> request, UUID batchId) {

    public static <RQ> Input<RQ> of(TypedHttpRequest<RQ> request) {
      return new Input<>(request, null);
    }

    public static <RQ> Input<RQ> of(TypedHttpRequest<RQ> request, UUID batchId) {
      return new Input<>(request, batchId);
    }

    public static <RQ> DataType<Input<RQ>> dataType(DataType<RQ> requestPayloadType) {
      return new InputDataType<>(HttpDataType.forRequest(requestPayloadType));
    }

    private static final class InputDataType<RQ> implements DataType<Input<RQ>> {

      private final DataType<TypedHttpRequest<RQ>> requestType;
      private final DataType<UUID> batchIdType = DataType.uuid();

      private InputDataType(DataType<TypedHttpRequest<RQ>> requestType) {
        this.requestType = requestType;
      }

      @Override
      public String name() {
        return "(" + requestType.name() + ", uuid?)";
      }

      @Override
      public Input<RQ> unmarshal(byte[] value) {
        List<byte[]> split = DataType.split(value);
        return new Input<>(
            requestType.unmarshal(split.getFirst()),
            split.size() > 1 ? batchIdType.unmarshal(split.get(1)) : null
        );
      }

      @Override
      public byte[] marshal(Input<RQ> value) {
        return DataType.concat(
            requestType.marshal(value.request()),
            value.batchId() != null ? batchIdType.marshal(value.batchId()) : null
        );
      }

      @Override
      public boolean equals(Object o) {
        return o instanceof InputDataType<?> that && Objects.equals(requestType, that.requestType);
      }

      @Override
      public int hashCode() {
        return Objects.hashCode(requestType);
      }
    }
  }

}
