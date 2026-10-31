package com.github.thxmasj.statemachine.http.outbox;

import static com.github.thxmasj.statemachine.IndexEntityModel.referenceEventType;
import static com.github.thxmasj.statemachine.database.mssql.Mappers.eventTypesFor;

import com.github.thxmasj.statemachine.DataType;
import com.github.thxmasj.statemachine.EventLog;
import com.github.thxmasj.statemachine.EventType;
import com.github.thxmasj.statemachine.IndexEntityModel;
import com.github.thxmasj.statemachine.State;
import com.github.thxmasj.statemachine.TransitionModelBuilder;
import com.github.thxmasj.statemachine.TransitionModelBuilder.TransitionModel;
import com.github.thxmasj.statemachine.message.http.HttpRequestMessage;
import com.github.thxmasj.statemachine.message.http.TypedHttpRequest;
import com.github.thxmasj.statemachine.message.http.TypedHttpResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public sealed interface HttpOutboxRequest<I> extends com.github.thxmasj.statemachine.EntityModel permits AtLeastOnce, AtMostOnce {

  Map<State, List<TransitionModelBuilder.TransitionModel<?, ?>>> transitions();

  default List<EventType<?, ?>> eventTypes() {
    return transitions().values()
        .stream()
        .flatMap(tl -> tl.stream())
        .flatMap(t -> eventTypesFor(t).stream())
        .distinct()
        .toList();
  }

  EventType<I, ?> requestDispatched();

  DataType<?> requestPayloadType();

  DataType<?> responsePayloadType();

  TypedHttpRequest<?> request(EventLog log);

  TypedHttpResponse<?> response(EventLog log);

  static AtLeastOnceBuilder.RequestPayloadTypeStep atLeastOnce() {
    return AtLeastOnceBuilder.create();
  }

  static AtMostOnceBuilder.RequestPayloadTypeStep atMostOnce() {
    return AtMostOnceBuilder.create();
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



}
